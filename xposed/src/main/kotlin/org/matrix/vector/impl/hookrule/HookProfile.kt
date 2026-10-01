/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * A module's hook profile for one target app (one package, one user).
 *
 * Scope says "load module M into app A". A profile says what M does once it is there: which of its
 * hooks install, on which backends, with which per-hook payloads, and what happens to hooks the
 * profile does not mention. Profiles are the unit the daemon computes and the process applies; they
 * are deliberately plain data so they can be built on one side and read on the other without either
 * depending on the other's classes.
 */
package org.matrix.vector.impl.hookrule

/**
 * The complete rule set for one (module, target) pair.
 *
 * [rules] is keyed by [HookKey] for O(1) lookup on the installation path. The class is immutable;
 * the store that holds it swaps whole instances rather than mutating, matching how scope snapshots
 * and hook registries already behave.
 */
class HookProfile private constructor(
    val modulePackage: String,
    val targetPackage: String,
    val userId: Int,
    private val rules: Map<HookKey, HookRule>,
    val defaultPolicy: HookDefaultPolicy,
) {
    /**
     * Whether the hook named by [layer]/[hookId] installs for this module in this target.
     *
     * A hook with an explicit rule follows it; a hook without one follows [defaultPolicy]. This is
     * the single decision point the installation path consults, so the two mechanisms cannot
     * disagree.
     */
    fun isEnabled(hookId: String, layer: HookLayer): Boolean {
        val rule = rules[HookKey(modulePackage, layer, hookId)] ?: return defaultAllows()
        return rule.enabled
    }

    /** The requested backend for a hook, or [HookBackend.AUTO] when it has no explicit rule. */
    fun backendFor(hookId: String, layer: HookLayer): HookBackend =
        rules[HookKey(modulePackage, layer, hookId)]?.backend ?: HookBackend.AUTO

    /** The opaque per-hook payload for a hook, or `null` when it has none. */
    fun configFor(hookId: String, layer: HookLayer): String? =
        rules[HookKey(modulePackage, layer, hookId)]?.config

    /** The rule for a hook, or `null` when it has none. */
    fun ruleFor(hookId: String, layer: HookLayer): HookRule? =
        rules[HookKey(modulePackage, layer, hookId)]

    /** Every explicit rule, for enumeration by the manager or a module's own UI. */
    fun allRules(): Collection<HookRule> = rules.values

    private fun defaultAllows(): Boolean = defaultPolicy == HookDefaultPolicy.ALLOW_ALL

    /** A profile that mentions nothing and lets everything through — the pre-rules behaviour. */
    fun copyWith(
        rules: Map<HookKey, HookRule> = this.rules,
        defaultPolicy: HookDefaultPolicy = this.defaultPolicy,
    ): HookProfile = HookProfile(modulePackage, targetPackage, userId, rules, defaultPolicy)

    companion object {
        /**
         * The profile every module gets when no rules have been configured: nothing listed, and
         * everything that is not listed installs. This is the object that makes the feature
         * backwards compatible — under it [isEnabled] returns `true` for every hook, and the
         * installation path behaves exactly as it did before rules existed.
         */
        @JvmField
        val LEGACY_ALLOW_ALL = HookProfile(
            modulePackage = "",
            targetPackage = "",
            userId = 0,
            rules = emptyMap(),
            defaultPolicy = HookDefaultPolicy.ALLOW_ALL,
        )

        /**
         * Build a profile from a flat rule list. Later rules with the same key replace earlier ones,
         * so a caller can layer a base set and an override without de-duplicating first.
         */
        fun of(
            modulePackage: String,
            targetPackage: String,
            userId: Int,
            rules: Iterable<HookRule>,
            defaultPolicy: HookDefaultPolicy = HookDefaultPolicy.ALLOW_ALL,
        ): HookProfile {
            val map = LinkedHashMap<HookKey, HookRule>()
            for (rule in rules) map[rule.key] = rule
            return HookProfile(modulePackage, targetPackage, userId, map, defaultPolicy)
        }
    }
}