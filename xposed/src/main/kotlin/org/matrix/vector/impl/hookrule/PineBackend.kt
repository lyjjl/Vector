/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The Pine backend: the second and last ART engine.
 *
 * Pine and LSPlant are two ways to hook the same ArtMethod, so exactly one of them may own a given
 * java executable in a process at a time; the selector guarantees that, and this file only has to
 * make Pine look like an install the rest of the framework already understands.
 *
 * The shape mismatch is the whole difficulty. Vector runs a *chain* - an ordered list of records,
 * each an `intercept(Chain)` that may call `proceed()` any number of times - while Pine offers a
 * single before/after pair around the original. Rather than try to split a chain across that pair
 * (impossible: the chain decides at runtime whether it calls through at all), we run the whole chain
 * inside Pine's `beforeCall` and hand Pine the answer. Pine's own original-call path is then always
 * suppressed by the result we set, so the original runs exactly where the chain's terminal says it
 * does, once, through `Pine.invokeOriginalMethod`.
 *
 * The consequence a reader should know: the whole hook, including any after-the-fact observation a
 * Hooker does after `proceed()`, happens before Pine's `afterCall`, and `afterCall` is unused. A
 * Hooker cannot tell the difference - it only ever sees Chain semantics - but a Pine-level profiler
 * watching before/after timestamps would see both edges collapse. That is the price of running the
 * LSPlant-oriented chain engine on a Pine trampoline, and it is paid deliberately: keeping one chain
 * engine for both engines is worth more than Pine's before/after split, which nothing in the Xposed
 * API surface exposes.
 */
package org.matrix.vector.impl.hookrule

import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.impl.hooks.VectorChain
import org.matrix.vector.impl.hooks.VectorHookRecord
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * Installs and removes Pine hooks on behalf of the selector.
 *
 * A single object, like the selector, and for the same reason: the bookkeeping here (which
 * executable Pine already owns) must be process-wide, not per call site.
 */
object PineBackend {

    /**
     * Executable -> the Pine unhook handle its install returned.
     *
     * Pine's `hook` returns a `MethodHook.Unhook`, and removing a Pine hook means calling `unhook()`
     * on exactly that object - there is no "unhook by member" entry point. We therefore have to
     * remember it, keyed by the executable so a later `unhookMethodWithBackend` can find it from the
     * same `Executable` the module passed in.
     */
    private val installed = ConcurrentHashMap<Executable, MethodHook.Unhook>()

    /** True when Pine can be used at all in this process. */
    fun isAvailable(): Boolean =
        try {
            Pine.isInitialized() || runCatching { Pine.ensureInitialized() }.isSuccess
        } catch (_: Throwable) {
            false
        }

    /**
     * Install one chain on [origin] through Pine, or return false if Pine refused.
     *
     * [records] is the full ordered chain snapshot, the same objects the LSPlant path would use,
     * because Pine is an alternative *trampoline*, not an alternative chain engine.
     */
    fun hook(origin: Executable, records: Array<VectorHookRecord>): Boolean {
        val callback =
            object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    val thisObj = callFrame.thisObject
                    val args = callFrame.args ?: emptyArray()

                    // The chain's terminal: Pine runs the original for us, and we translate its
                    // result or throwable into the frame. The cause is rethrown rather than the
                    // InvocationTargetException, because the chain treats what it sees as the
                    // method's own failure, and an ITE wrapper would be visible to a Hooker.
                    val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                        try {
                            Pine.invokeOriginalMethod(origin, tObj, *tArgs)
                        } catch (ite: InvocationTargetException) {
                            throw ite.cause ?: ite
                        }
                    }

                    val chain = VectorChain(origin, thisObj, args, records, 0, terminal)
                    try {
                        val result = chain.proceed()
                        // setResult marks the frame returnEarly, so Pine does not also run the
                        // original. For a void method a null result carries no information, so we
                        // skip the call and leave the frame to Pine's normal path.
                        if (result != null || origin !is Method || origin.returnType != Void.TYPE) {
                            callFrame.setResult(result)
                        }
                    } catch (t: Throwable) {
                        callFrame.setThrowable(t)
                    }
                }
            }

        return try {
            installed[origin] = Pine.hook(origin, callback)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Remove the Pine hook on [origin], if this backend owns one there. */
    fun unhook(origin: Executable): Boolean {
        val unhook = installed.remove(origin) ?: return false
        return try {
            unhook.unhook()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Whether Pine currently owns [origin]. */
    fun owns(origin: Executable): Boolean = installed.containsKey(origin)
}
