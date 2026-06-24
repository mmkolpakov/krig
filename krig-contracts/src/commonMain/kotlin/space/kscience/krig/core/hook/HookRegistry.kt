@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@file:MustUseReturnValues

package space.kscience.krig.core.hook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.concurrent.atomics.AtomicReference

/**
 * A closeable registration returned by [HookRegistry.register]. Close it to unsubscribe;
 * forgetting is safe — leaked handlers are collected together with the registry.
 */
public fun interface HookRegistration : AutoCloseable

/**
 * Carries [Hook] handlers. Implemented by operation pipeline builders and
 * `DeviceHub` (topology-scope). Third-party consumers that own their own firing sites
 * implement it too.
 */
public interface HookRegistry {
    /**
     * Registers [handler] for [hook] and returns a [HookRegistration] that removes it on close. The
     * handle is [IgnorableReturnValue]: discard it to keep the handler for the firing site's whole
     * lifetime (safe — leaked handlers are collected with the registry), or prefer the scope-bound
     * `register(scope, hook, handler)` extension, which removes the handler when the subscriber's
     * scope completes.
     */
    @IgnorableReturnValue
    public fun <H : Any> register(hook: Hook<H>, handler: H): HookRegistration

    /** Removes a previously-registered [handler] for [hook]. No-op if absent. */
    public fun <H : Any> off(hook: Hook<H>, handler: H)

    public fun <H : Any> handlersOf(hook: Hook<H>): List<H>

    /** `true` when no handler is registered for any hook. */
    public fun isEmpty(): Boolean

    public companion object {
        /** Creates a lock-free copy-on-write registry for ordinary runtime use. */
        public fun buffered(): HookRegistry = BufferedHookRegistry()
    }
}

/**
 * Registers [handler] for [hook] and binds its lifetime to [scope]: when [scope]'s [Job] completes or
 * is cancelled, the handler is removed automatically. Closing the returned [HookRegistration] earlier
 * removes the handler and detaches the completion callback (so a finished scope does not retain it).
 *
 * This is the recommended ownership model: the *subscriber's* scope owns the registration, so a
 * forgotten `close()` cannot leak the handler for the registry's whole lifetime. If [scope] has no
 * [Job] (e.g. a bare `CoroutineScope(EmptyCoroutineContext)`), this degrades to a plain [register] and
 * the caller owns the returned handle.
 */
public fun <H : Any> HookRegistry.register(
    scope: CoroutineScope,
    hook: Hook<H>,
    handler: H,
): HookRegistration {
    val registration = register(hook, handler)
    val job = scope.coroutineContext[Job] ?: return registration
    val completionHandle = job.invokeOnCompletion { registration.close() }
    return HookRegistration {
        completionHandle.dispose()
        registration.close()
    }
}

/**
 * CAS-ed [HookRegistry]. Typed-key heterogeneous map storing `Hook<H> → List<H>`;
 * the cast in [handlersOf] is safe by construction.
 */
internal class BufferedHookRegistry : HookRegistry {
    private val state: AtomicReference<Map<Hook<*>, List<Any>>> = AtomicReference(emptyMap())

    @IgnorableReturnValue
    override fun <H : Any> register(hook: Hook<H>, handler: H): HookRegistration {
        while (true) {
            val prev = state.load()
            val prior = prev[hook].orEmpty()
            val next = prev + (hook to prior + handler)
            if (state.compareAndSet(prev, next)) break
        }
        return HookRegistration { off(hook, handler) }
    }

    override fun <H : Any> off(hook: Hook<H>, handler: H) {
        while (true) {
            val prev = state.load()
            val prior = prev[hook] ?: return
            val idx = prior.indexOfFirst { it === handler }
            if (idx < 0) return
            val remaining = prior.toMutableList().apply { removeAt(idx) }
            val next = if (remaining.isEmpty()) prev - hook else prev + (hook to remaining)
            if (state.compareAndSet(prev, next)) return
        }
    }

    override fun <H : Any> handlersOf(hook: Hook<H>): List<H> {
        val slot = state.load()[hook] ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return slot as List<H>
    }

    override fun isEmpty(): Boolean = state.load().isEmpty()
}
