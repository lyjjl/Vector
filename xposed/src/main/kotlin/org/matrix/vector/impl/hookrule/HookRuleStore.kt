/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The process-local rule store and the device capability set.
 *
 * There is one store per process. The daemon fills it once at injection time, before any module
 * entry class runs, and nothing mutates it afterwards, so a plain volatile swap is sufficient.
 *
 * The store answers only the *installation* question — does this hook install, with which rules —
 * and never the dispatch question, which the chain engine owns. Backend *selection* lives in
 * [HookBackendSelector]; what lives here is the raw rule data and the capability set the selector
 * consults.
 */
package org.matrix.vector.impl.hookrule

import java.util.concurrent.ConcurrentHashMap

object HookRuleStore {

    @Volatile
    private var profiles: Map<String, HookProfile> = emptyMap()

    /**
     * Backends this build can install at all, *statically*: LSPlant and Dobby are always present —
     * they are compiled into the native bridge — and Pine is declared here because it is a real
     * dependency of every build that ships this module.
     *
     * This set is deliberately the "is it in the build" half of the question and nothing more. The
     * "can this process use it right now" half is answered elsewhere and differs per backend:
     *
     *  - KPM is added and removed by [markKpmAvailable], because its availability is a device and
     *    boot fact the native bridge reports. It being absent is normal, not an error.
     *  - Pine is *not* tracked here even though it is in the build, because being compiled in does
     *    not mean its JNI library has been loaded into this process. [isAvailable] asks
     *    [PineBackend] directly for that, so the two facts cannot drift.
     */
    private val capabilities: MutableSet<HookBackend> =
        ConcurrentHashMap.newKeySet<HookBackend>().apply {
            add(HookBackend.LSPLANT)
            add(HookBackend.PINE)
            add(HookBackend.DOBBY)
        }

    /** The target package this process belongs to. Set once at injection; "" until then. */
    @Volatile var targetPackage: String = ""
        private set

    /** The user id this process runs as. Set once at injection; 0 until then. */
    @Volatile var userId: Int = 0
        private set

    /** Record the target identity, so a [HookSite] can be built without re-deriving it. */
    fun installTarget(packageName: String, uid: Int) {
        targetPackage = packageName
        userId = uid
    }

    /** The profile for [modulePackage], or the allow-all legacy profile when none is registered. */
    fun profileFor(modulePackage: String): HookProfile =
        profiles[modulePackage] ?: HookProfile.LEGACY_ALLOW_ALL

    /** Replace the whole map. Called once, from injection, before any module entry class runs. */
    fun install(profiles: Map<String, HookProfile>) {
        this.profiles = profiles.toMap()
    }

    /** Register a single module's profile without disturbing the others. */
    fun put(profile: HookProfile) {
        profiles = profiles + (profile.modulePackage to profile)
    }

    /** True when the current target has at least one non-default profile. Diagnostics only. */
    fun hasAnyRules(): Boolean = profiles.values.any { it.allRules().isNotEmpty() }

    /**
     * Records whether the traceless (KPM) backend is armed and usable in this process. Called by the
     * native layer once its bridge probe succeeds.
     *
     * This is a preference toggle, not a gate on native hooking: the KPM being absent means the
     * selector falls back to Dobby for native hooks, not that native hooks are refused.
     */
    fun markKpmAvailable(available: Boolean) {
        if (available) capabilities.add(HookBackend.KPM) else capabilities.remove(HookBackend.KPM)
    }

    /**
     * Whether [backend] can be installed right now.
     *
     * PINE is special-cased to ask the engine itself rather than the static set: a build may ship
     * Pine and still be in a process where its library has not been pulled in, and the selector must
     * see that as unavailable. Folding it into `capabilities` would make the set mean two different
     * things depending on which backend asked.
     */
    fun isAvailable(backend: HookBackend): Boolean =
        when (backend) {
            HookBackend.AUTO -> true
            HookBackend.PINE -> PineBackend.isAvailable()
            else -> backend in capabilities
        }

    /** Every backend currently available, for the manager to grey out the rest. */
    fun availableBackends(): Set<HookBackend> = capabilities.toSet()

    /**
     * Build the [HookSite] for a hook of [modulePackage]. The target half comes from the process
     * identity recorded at injection, so every site in this process shares it — which is what makes
     * a manager's per-app view of a hook line up with what the app's own process selected.
     */
    fun siteFor(modulePackage: String, layer: HookLayer, hookId: String): HookSite =
        HookSite(targetPackage, userId, modulePackage, layer, hookId)
}