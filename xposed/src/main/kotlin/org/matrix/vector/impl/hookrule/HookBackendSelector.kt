/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The backend selector: one authoritative decision per (target, module, layer, hook), plus the book
 * keeping that keeps a hook owned by exactly one backend.
 *
 * Why a selector rather than a simple `when` at each call site:
 *
 *  - **Redundancy.** Two code paths can ask to hook the same method — a module may call intercept()
 *    twice, a hot reload may reinstall a hook the old generation already placed, or a native and an
 *    ART hook can name the same reflected member. Without an owner, both install, and the method
 *    ends up with two trampolines. The selector records the owner so the second request folds into
 *    the first instead of doubling it.
 *
 *  - **Conflict.** Layer assignment is a property of the *target*, not of who asked. A reflected
 *    `Executable` is always ART; a raw function pointer is always NATIVE. The selector refuses a
 *    request that names the wrong layer for what is being hooked, rather than letting the two layers
 *    race over the same address.
 *
 *  - **Determinism.** A given (target, hook id) must always resolve to the same backend, in every
 *    process, for the life of the process — otherwise a manager that shows "hook X is on PINE" would
 *    be describing a different install than the one that actually happened. The selector memoises
 *    the first resolution and answers every later request from it.
 *
 *  - **Single backend per layer per app area.** Within one (target package, user id) — one
 *    application's process — a layer may have at most one active backend. The first selection on an
 *    area's layer locks it; a later, *different* backend asking for the same area's layer is refused
 *    with [SelectionFailure.AreaBackendLocked] rather than installed alongside. This is stronger
 *    than per-site ownership: it is not enough that one hook is served by one engine, the whole
 *    area's layer has to agree, because ART has one entry-point slot per method and the native side
 *    has one inline-patch of the process's text — two engines competing for those do not compose.
 *
 * Ownership is keyed by (target key, layer, hook id): all three matter. The same hook id on two
 * layers is two hooks; the same hook id in two target packages is two hooks. The area lock is keyed
 * coarser, by (target key, layer), because it is about the layer as a whole rather than one site.
 */
package org.matrix.vector.impl.hookrule

import java.util.concurrent.ConcurrentHashMap

/**
 * Identifies one hook installation site, independent of who is installing it.
 *
 * [targetPackage] and [userId] are what make the decision per-app rather than per-device: the same
 * module hooking the same method in two apps is two independent selections, so a device that arms
 * the KPM for one app and not another does not have one app's choice leak into the other's.
 */
data class HookSite(
    val targetPackage: String,
    val userId: Int,
    val modulePackage: String,
    val layer: HookLayer,
    val hookId: String,
)

/** What a site resolves to once selected. Immutable; stored in the memo table. */
data class HookSelection(val site: HookSite, val backend: HookBackend)

/**
 * The process-wide selector.
 *
 * All three maps are concurrent and write-once per key in the steady state: a site is selected the
 * first time it is hooked, and every later request for that site is a read. The only writer after
 * that is [release], which drops the ownership when the last hook on a site is removed so the site
 * can be re-selected if it is hooked again (after a hot reload, say).
 */
object HookBackendSelector {

    /** Site -> the backend that owns it. The memo that makes selection deterministic. */
    private val owners = ConcurrentHashMap<HookSite, HookBackend>()

    /**
     * Target keys currently claimed on each layer, used to refuse a second, *different* hook id
     * trying to take over the same executable through a different layer. Keyed by the executable's
     * stable identity, not by hook id, because the point is to catch two ids naming one address.
     */
    private val claimedByLayer = ConcurrentHashMap<String, HookLayer>()

    /**
     * (target package, user id, layer) -> the one backend that layer of that application uses.
     *
     * This is the single-backend-per-layer-per-area lock. It is keyed coarser than [owners] and
     * filled before ownership is taken, so the first hook to select on an area's layer fixes the
     * engine for every later hook in the same app — including hooks from other modules, which is
     * the point: a module cannot introduce a second ART engine into an app already running LSPlant,
     * however independently it names its own hooks.
     *
     * A record is removed by [release] only when the area's layer has no hooks left at all, so the
     * lock is as durable as the longest-lived hook on that layer rather than the first to be torn
     * down.
     */
    private val areaLayerBackend = ConcurrentHashMap<AreaLayer, HookBackend>()

    /** Identifies one application area's layer: the granularity the single-backend rule applies at. */
    private data class AreaLayer(val targetPackage: String, val userId: Int, val layer: HookLayer) {
        companion object {
            fun of(site: HookSite) = AreaLayer(site.targetPackage, site.userId, site.layer)
        }
    }

    /**
     * Select the backend for [site], or fail if the request cannot be honoured.
     *
     * The returned selection is the single, authoritative answer for that site for the rest of the
     * process. Callers hold it; they do not re-derive it.
     */
    fun select(site: HookSite, requested: HookBackend, executableKey: String): SelectionResult {
        // A request that names a backend from the other layer is a programming error, not a device
        // limitation, and must not be papered over: hooking a Java method "with KPM" is meaningless.
        val requestedLayer = requested.layer
        if (requestedLayer != null && requestedLayer != site.layer) {
            val f = SelectionFailure.WrongLayer(requested, site.layer)
            return SelectionResult.Failed(f.describe(), f)
        }

        // The area's layer picks the engine for the whole application, so a request that names a
        // concrete backend has to agree with whatever this layer already committed to. Checking it
        // before AUTO is resolved means an explicit PINE on an area already running LSPlant is
        // refused for the *right* reason (the area is locked) rather than being steered to LSPlant
        // and silently getting a different engine than it asked for.
        val area = AreaLayer.of(site)
        val incumbent = areaLayerBackend[area]
        if (incumbent != null && requested != HookBackend.AUTO && requested != incumbent) {
            val f = SelectionFailure.AreaBackendLocked(site.layer, incumbent, requested)
            return SelectionResult.Failed(f.describe(), f)
        }

        // Resolve AUTO within the site's layer. On ART that is LSPlant, unless the area has already
        // locked onto Pine. On the native layer it is the KPM when the kernel module is armed —
        // the traceless engine is always preferred when present — and Dobby otherwise, because a
        // native hook is a thing this framework can always install, tracer or not.
        val preferred =
            when (site.layer) {
                HookLayer.ART ->
                    if (incumbent == HookBackend.PINE) HookBackend.PINE else HookBackend.LSPLANT
                HookLayer.NATIVE ->
                    if (HookRuleStore.isAvailable(HookBackend.KPM)) HookBackend.KPM
                    else HookBackend.DOBBY
            }
        val candidate = if (requested == HookBackend.AUTO) preferred else requested

        // Whatever the candidate is, it has to exist in this process. This is now a plain
        // availability check for any backend rather than a special case for the KPM: no engine is
        // mandatory, so "not available" means "pick another", not "install nothing".
        if (!HookRuleStore.isAvailable(candidate)) {
            val f = SelectionFailure.BackendUnavailable(candidate)
            return SelectionResult.Failed(f.describe(), f)
        }

        // Pine is compiled in but not necessarily *loadable*: its JNI library has to have been
        // pulled into this process before its entry points resolve, and a target where that has not
        // happened must not be handed a Pine install that will throw at the first hooked call. The
        // capability set says "this build contains Pine"; this probe says "this process can use it
        // right now", and only the second is what an install decision may rest on.
        if (candidate == HookBackend.PINE && !PineBackend.isAvailable()) {
            val f = SelectionFailure.BackendUnavailable(candidate)
            return SelectionResult.Failed(f.describe(), f)
        }

        // Layer exclusivity for one executable. If a *different* site already claimed this
        // executable on the other layer, refuse rather than let two layers fight over one address.
        val priorLayer = claimedByLayer[executableKey]
        if (priorLayer != null && priorLayer != site.layer) {
            val f = SelectionFailure.ExecutableConflict(executableKey, priorLayer)
            return SelectionResult.Failed(f.describe(), f)
        }

        // Take the area lock, then the site. The lock is claimed first because it is the coarser
        // promise: if two threads race to select two different backends on one area's layer, exactly
        // one wins the lock and the other is refused below, even though they are naming different
        // sites and so would never collide in `owners`. Only the thread that actually installed the
        // winner may proceed; a loser here has installed nothing and must not take a site.
        val lockWinner = areaLayerBackend.putIfAbsent(area, candidate)
        if (lockWinner != null && lockWinner != candidate) {
            val f = SelectionFailure.AreaBackendLocked(site.layer, lockWinner, candidate)
            return SelectionResult.Failed(f.describe(), f)
        }

        // Ownership of the site. putIfAbsent is the whole redundancy guard: the first caller to win a
        // site owns it, and every later caller folds into that owner instead of installing a second
        // trampoline. Returning the owner's backend (not the candidate) is what makes the answer
        // deterministic even when a later caller asks for something else.
        val existing = owners.putIfAbsent(site, candidate)
        claimedByLayer.putIfAbsent(executableKey, site.layer)
        return SelectionResult.Selected(existing ?: candidate)
    }

    /**
     * Whether [site] is already owned by another backend, i.e. this request is a redundant one that
     * should fold into the existing install rather than create a new one. Used by the installation
     * path to skip a native hook that a previous request already placed.
     */
    fun isRedundant(site: HookSite): Boolean = owners.containsKey(site)

    /** The backend that currently owns [site], or `null` if it is unclaimed. */
    fun ownerOf(site: HookSite): HookBackend? = owners[site]

    /**
     * Drop ownership of [site]. Called when the last hook on a site is removed, so a later install
     * of the same site is a fresh selection rather than a fold into a stale owner.
     *
     * [executableKey] is released only when no other site on the same layer still claims it, so a
     * page of ART hooks unhooking one at a time does not free the executable for the native layer
     * while the rest are still installed.
     *
     * The area lock is released on the same condition, and deliberately no earlier: while any hook
     * on the area's layer is still installed, that layer is still running that backend, and letting
     * a new selection pick a different one would leave the first generation's trampolines in place
     * beside the new engine's.
     */
    fun release(site: HookSite, executableKey: String) {
        owners.remove(site)
        val stillClaimed = owners.keys.any { it.layer == site.layer && it.targetPackage == site.targetPackage && it.userId == site.userId }
        if (!stillClaimed) {
            claimedByLayer.remove(executableKey)
            areaLayerBackend.remove(AreaLayer.of(site))
        }
    }

    /**
     * The backend [site]'s application area has committed its layer to, or `null` if the layer is
     * still free. Read-only view for the manager and for diagnostics.
     */
    fun areaBackendOf(site: HookSite): HookBackend? = areaLayerBackend[AreaLayer.of(site)]

    /** Clear every decision. Test/diagnostic hook; not called in normal operation. */
    fun reset() {
        owners.clear()
        claimedByLayer.clear()
        areaLayerBackend.clear()
    }
}