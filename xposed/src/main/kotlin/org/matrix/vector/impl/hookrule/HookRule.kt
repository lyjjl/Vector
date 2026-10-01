/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Per-hook rules: the fine-grained switch that sits below a module's scope.
 *
 * Vector already answers "which apps does this module run in" (scope). A rule answers the next
 * question down: "once running there, which of the module's hooks are actually installed, and on
 * which backend". One rule names one hook of one module for one target package/user.
 *
 * A module's hooks are identified by [hookId]. The id is either what the module passed to
 * `HookBuilder#setId` or, when it passed none, a stable synthetic id derived from the executable
 * (declaring class + name + parameter types). Either way the id is stable across processes and
 * across module reloads, which is what lets a rule written in the manager name a hook the module
 * installs in the target process.
 */
package org.matrix.vector.impl.hookrule

/**
 * One decision about one hook of one module in one target app.
 *
 * Immutable and cheap to copy; rules are read on the hook-installation path (once per hook, not per
 * call), so no defensive copying is done at read time — callers treat instances as values.
 */
data class HookRule(
    /** The module the hook belongs to. Package name, matching scope and the daemon tables. */
    val modulePackage: String,

    /** The id of the individual hook (`setId`, or the synthetic `Class#method(sig)`). */
    val hookId: String,

    /** Which layer the hook is on. Used to disambiguate ids that could collide across layers. */
    val layer: HookLayer,

    /** Whether the hook is installed at all in this target. `false` means "declared, not applied". */
    val enabled: Boolean = true,

    /** Requested backend. Resolved against the device when the hook is installed. */
    val backend: HookBackend = HookBackend.AUTO,

    /**
     * Free-form per-hook payload handed back to the module (via `XposedModule#getHookConfig`). Opaque
     * to the framework; the manager stores it, the module interprets it. `null` means "no payload".
     */
    val config: String? = null,
) {
    /** Identity used for lookup: a rule is keyed by (module, layer, hookId). */
    val key: HookKey get() = HookKey(modulePackage, layer, hookId)

    /** The same rule with [enabled] forced to [value]. */
    fun withEnabled(value: Boolean): HookRule = copy(enabled = value)
}

/**
 * Lookup key for a rule. A plain data class so `HashMap`/`Set` arithmetic works over rules the way
 * scope arithmetic already works over `ScopeEntry`.
 */
data class HookKey(
    val modulePackage: String,
    val layer: HookLayer,
    val hookId: String,
)

/**
 * How to treat hooks a module installs that have *no* explicit rule.
 *
 * This is the same shape as the decision scope makes for apps: an explicit allow-list is precise but
 * must be maintained, an explicit deny-list is short and fails open. The default is [ALLOW_ALL],
 * which is what every module got before rules existed and therefore the only value that keeps
 * existing behaviour byte-for-byte identical.
 */
enum class HookDefaultPolicy {
    /** Unlisted hooks install normally. Backwards compatible; the default. */
    ALLOW_ALL,

    /** Unlisted hooks do not install. The module names the ones it wants. */
    DENY_ALL,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): HookDefaultPolicy =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALLOW_ALL
    }
}