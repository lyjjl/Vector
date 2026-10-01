package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.util.Collections
import org.matrix.vector.impl.hookrule.HookBackend
import org.matrix.vector.util.Utils

/**
 * A registered hook configuration, stored natively by [HookBridge].
 *
 * Immutable, and that is what makes the chain snapshot based for free. Replacing a hook swaps this
 * whole object inside the native callback map rather than editing it, so an array
 * `callbackSnapshot` already copied for a call in flight keeps pointing at the record that call
 * started with. Making the hooker mutable instead would force every hooked call to freeze a hooker
 * array of its own, which is an allocation on the hottest path in the framework.
 */
class VectorHookRecord(
    val hooker: Hooker,
    val priority: Int,
    val exceptionMode: ExceptionMode,
    val id: String?,
    /**
     * True for a record that was minted for a hook a profile disabled. Such a record is never
     * handed to the native layer: it exists only so the module's handle has something to point at,
     * and it is what [VectorHookHandle.unhook] checks before deciding there is nothing to remove.
     */
    val disabled: Boolean = false,
    /**
     * Which ART engine installed this record. Defaults to [HookBackend.LSPLANT] because every
     * record that predates Pine support was an LSPlant one, and the native bridge is the only
     * path that mints a record without naming an engine.
     *
     * An unhook reads this rather than re-asking the selector: the selector answers "what should
     * own this site", but at unhook time the question is "what *did* own it", and a profile edit
     * between install and unhook must not be able to change the answer and strand the install.
     */
    val backend: HookBackend = HookBackend.LSPLANT,
) {
    /** The same record marked as coming from a disabled hook. */
    fun withDisabledFlag(): VectorHookRecord =
        VectorHookRecord(hooker, priority, exceptionMode, id, disabled = true, backend = backend)
}

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces [ExceptionMode]
 * protections.
 */
class VectorChain(
    private val executable: Executable,
    private val thisObj: Any?,
    private val args: Array<Any?>,
    private val hooks: Array<VectorHookRecord>,
    private val hookIndex: Int,
    private val terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
) : Chain {

    // Tracks if this specific chain node has forwarded execution downstream
    internal var proceedCalled: Boolean = false
        private set

    // Stores the actual result/exception from the rest of the chain/original method
    internal var downstreamResult: Any? = null
    internal var downstreamThrowable: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObj

    override fun getArgs(): List<Any?> = Collections.unmodifiableList(args.toMutableList())

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = internalProceed(thisObj, args)

    override fun proceed(currentArgs: Array<Any?>): Any? = internalProceed(thisObj, currentArgs)

    override fun proceedWith(thisObject: Any): Any? = internalProceed(thisObject, args)

    override fun proceedWith(thisObject: Any, currentArgs: Array<Any?>): Any? =
        internalProceed(thisObject, currentArgs)

    private fun internalProceed(thisObject: Any?, currentArgs: Array<Any?>): Any? {
        proceedCalled = true

        // Reached the end of the modern hooks; trigger the original executable (and legacy hooks)
        if (hookIndex >= hooks.size) {
            return executeDownstream { terminal(thisObject, currentArgs) }
        }

        val record = hooks[hookIndex]
        val hooker = record.hooker
        val exceptionMode = record.exceptionMode
        val nextChain =
            VectorChain(executable, thisObject, currentArgs, hooks, hookIndex + 1, terminal)

        return try {
            executeDownstream { hooker.intercept(nextChain) }
        } catch (t: Throwable) {
            executeDownstream {
                handleInterceptorException(
                    t,
                    hooker,
                    exceptionMode,
                    nextChain,
                    thisObject,
                    currentArgs,
                )
            }
        }
    }

    /**
     * Executes the block and caches the downstream state so parent chains can recover it if the
     * current interceptor crashes during post-processing.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            downstreamThrowable = null
            result
        } catch (t: Throwable) {
            downstreamResult = null
            downstreamThrowable = t
            throw t
        }
    }

    /** Handles exceptions thrown by a hooker according to its [ExceptionMode]. */
    private fun handleInterceptorException(
        t: Throwable,
        hooker: Hooker,
        exceptionMode: ExceptionMode,
        nextChain: VectorChain,
        recoveryThis: Any?,
        recoveryArgs: Array<Any?>,
    ): Any? {
        // Check if the exception originated from downstream (lower hooks or original method)
        if (nextChain.proceedCalled && t === nextChain.downstreamThrowable) {
            throw t
        }

        // Passthrough mode does not rescue the process from hooker crashes
        if (exceptionMode == ExceptionMode.PASSTHROUGH) {
            throw t
        }

        val hookerName = hooker.javaClass.name
        if (!nextChain.proceedCalled) {
            // Crash occurred before calling proceed(); skip hooker and continue the chain
            Utils.logD("Hooker [$hookerName] crashed before proceed. Skipping.", t)
            return nextChain.internalProceed(recoveryThis, recoveryArgs)
        } else {
            // Crash occurred after calling proceed(); suppress and restore downstream state
            Utils.logD("Hooker [$hookerName] crashed after proceed. Restoring state.", t)
            nextChain.downstreamThrowable?.let { throw it }
            return nextChain.downstreamResult
        }
    }
}
