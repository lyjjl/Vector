/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Layers and backends for multi-layer, multi-backend hooking.
 *
 * There are exactly two layers a hook can be installed on, and a small, fixed set of backends per
 * layer. The set is deliberately small: each backend is a real, independent installation mechanism
 * with its own footprint, and the point of the selector is to pick one of them deterministically,
 * not to offer a menu of near-duplicates.
 *
 *   ART layer   (java method / constructor)
 *       └─ LSPLANT   LSPlant ArtMethod trampoline (entry-point rewrite + deopt). Default.
 *          PINE      Pine ART hook, the alternative ART engine. Opt-in per hook.
 *
 *   NATIVE layer (native function)
 *       └─ DOBBY     Dobby inline hook. The default native backend.
 *          KPM       Traceless inline hook via the KernelPatch module. Optional: usable when the
 *                    kernel module is present, and simply absent otherwise. It is the preferred
 *                    native backend for stealth, but it is not required to install a native hook.
 *
 * AUTO is a request, never a stored answer: the selector resolves it to a concrete backend on the
 * site's layer and memoises that.
 *
 * One rule shapes every decision here: **within one application area, a layer has at most one
 * active backend**. An "application area" is a (target package, user id) pair — the identity a
 * process already carries — so the ART side of com.example is served by one engine and its native
 * side by one engine, independently of whether either happens to be shared with another app. A
 * second hook asking for a different backend on an already-locked layer is refused rather than
 * honoured, because two engines installing on one process's layer is exactly the situation the
 * single-backend rule exists to prevent — two trampoline schemes, two unhook paths, and a manager
 * that cannot say which one a given call went through.
 */
package org.matrix.vector.impl.hookrule

/** Which layer a hook lives on. A property of the target, not of who asks. */
enum class HookLayer {
    /** A java method or constructor, reached through a reflected `Executable`. */
    ART,

    /** A native function, reached through a function pointer. */
    NATIVE,
}

/**
 * A concrete installation mechanism, or [AUTO] to let the selector choose on the layer.
 *
 * [layer] is the layer the backend belongs to; the selector refuses a request whose backend belongs
 * to the other layer, because hooking a java method "with the KPM" is a contradiction, not a device
 * limitation.
 */
enum class HookBackend(val layer: HookLayer?) {
    /** Let the selector pick: LSPLANT on the ART layer, KPM on the native layer. */
    AUTO(null),

    /** LSPlant: ArtMethod entry-point trampoline plus deoptimization. The ART default. */
    LSPLANT(HookLayer.ART),

    /** Pine: the alternative ART hook engine. Opt-in; same layer as LSPlant. */
    PINE(HookLayer.ART),

    /** Dobby: inline hook. The classic native backend. */
    DOBBY(HookLayer.NATIVE),

    /**
     * The KernelPatch (KPM) traceless backend: traps the target page with PTE_UXN and reroutes
     * execution to a recompiled clone in VMA-less ghost memory — no `.text` patch, no anonymous
     * executable mapping.
     *
     * Optional, not mandatory. A native hook may install through Dobby on any device; the KPM is
     * what makes it traceless, so it is preferred when the kernel module is present and simply
     * unavailable when it is not. The selector treats "KPM not armed" as "this backend is not
     * available", not as "no native hook is possible".
     */
    KPM(HookLayer.NATIVE),
    ;

    val isAuto: Boolean get() = this == AUTO

    companion object {
        /** Parse a serialized backend name, falling back to [AUTO] for anything unknown. */
        fun fromNameOrAuto(name: String?): HookBackend =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

/**
 * What actually happened, after resolving a request against the running device.
 *
 * A module never sees a request silently become something else: either the selection is [Selected]
 * with the concrete backend, or it is [Failed]. There is no "asked for one thing, got another"
 * state — a request the selector cannot honour is refused, never quietly served by a different
 * engine than the one the module named.
 */
sealed interface SelectionResult {
    /** The concrete backend that will install the hook. */
    data class Selected(val effective: HookBackend) : SelectionResult

    /** Why the request was refused; [reason] is a human-readable one-liner for the log. */
    data class Failed(val reason: String, val failure: SelectionFailure) : SelectionResult

    companion object {
        /** Fold a result into a backend or throw, for call sites that treat a failure as fatal. */
        fun getOrThrow(result: SelectionResult): HookBackend =
            when (result) {
                is Selected -> result.effective
                is Failed -> throw IllegalStateException(result.reason)
            }
    }
}

/** Why a selection could not be honoured. Each carries enough to name the offender in a log. */
sealed interface SelectionFailure {
    /** The request named a backend belonging to the other layer. */
    data class WrongLayer(val requested: HookBackend, val expected: HookLayer) : SelectionFailure {
        override fun describe() = "backend $requested cannot serve the $expected layer"
    }

    /** The requested backend is not usable on this device right now (e.g. KPM not armed). */
    data class BackendUnavailable(val backend: HookBackend) : SelectionFailure {
        override fun describe() = "backend $backend is not available on this device"
    }

    /** Two different hook ids tried to own the same executable through different layers. */
    data class ExecutableConflict(val executableKey: String, val heldBy: HookLayer) :
        SelectionFailure {
        override fun describe() = "$executableKey is already hooked on the $heldBy layer"
    }

    /** A hook with this id and layer is already owned; the request is redundant. */
    data class AlreadyOwned(val backend: HookBackend) : SelectionFailure {
        override fun describe() = "this site is already owned by $backend"
    }

    /**
     * The application area's layer is already served by a different backend.
     *
     * Distinct from [AlreadyOwned], which is the same site asked for twice. This is a *different*
     * site — another module, another hook id — asking for a backend the area's layer has already
     * committed to someone else. Two engines on one layer in one process is what the single-backend
     * rule forbids, so the later request is refused with the incumbent named, which is what lets a
     * module author understand they must ask for the same backend as the rest of the area.
     */
    data class AreaBackendLocked(
        val layer: HookLayer,
        val lockedTo: HookBackend,
        val requested: HookBackend,
    ) : SelectionFailure {
        override fun describe() =
            "this app's $layer layer is already served by $lockedTo, which cannot also serve $requested"
    }

    fun describe(): String
}