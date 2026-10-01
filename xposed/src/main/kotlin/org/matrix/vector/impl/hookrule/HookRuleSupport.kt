/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Helpers shared between the rule layer and the hook installation path.
 *
 * The only thing here that matters is [syntheticId]: a module is free to install a hook without
 * calling `setId`, and rules still have to be able to name it. Deriving the id from the executable
 * gives a name that is stable across processes, across the module's own reloads, and independent of
 * the order hooks happen to be installed in — which is what makes a rule written in the manager hit
 * the same hook in every target process.
 */
package org.matrix.vector.impl.hookrule

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

object HookRuleSupport {

    /**
     * A stable id for a hook that was registered without `setId`.
     *
     * Format for a method:      `Owner#name(param.Type,other.Type)`
     * Format for a constructor: `Owner#<init>(param.Type,...)`
     *
     * The fully-qualified parameter type names are included, so two overloads of the same method
     * name get distinct ids. The declaring class is included so an override in a subclass is a
     * different hook from the method it overrides — which it is.
     *
     * Only used when the module passed no id of its own: an explicit id always wins, because it is
     * the module telling us what to call this hook and the rule text will use that name.
     */
    @JvmStatic
    fun syntheticId(executable: Executable): String {
        val owner = executable.declaringClass.name
        val name =
            when (executable) {
                is Constructor<*> -> "<init>"
                is Method -> executable.name
                else -> executable.name.ifEmpty { "<method>" }
            }
        val params =
            executable.parameterTypes.joinToString(",") { it.name }
        return "$owner#$name($params)"
    }

    /**
     * A key that names the *address* a hook would occupy, for the selector's cross-layer
     * exclusivity check.
     *
     * It is deliberately the same string as [syntheticId]: both name one executable by its declaring
     * class, method name and parameter types, so a rule that names a hook and a layer-exclusivity
     * check that names the same executable agree on what they are talking about. The two are kept as
     * separate functions with the same body rather than one aliasing the other, because they answer
     * different questions and may diverge — an id is a name a module may override, a key is not.
     */
    @JvmStatic
    fun executableKey(executable: Executable): String = syntheticId(executable)
}