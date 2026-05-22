@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@file:MustUseReturnValues

package space.kscience.krig.core.hook

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
    /** Registers [handler] for [hook]. Same as [register] but without the removal handle. */
    public fun <H : Any> on(hook: Hook<H>, handler: H)

    /** Registers [handler] for [hook]; returns a [HookRegistration] that removes the handler on close. */
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
 * CAS-ed [HookRegistry]. Typed-key heterogeneous map storing `Hook<H> → List<H>`;
 * the cast in [handlersOf] is safe by construction.
 */
internal class BufferedHookRegistry : HookRegistry {
    private val state: AtomicReference<Map<Hook<*>, List<Any>>> = AtomicReference(emptyMap())

    override fun <H : Any> on(hook: Hook<H>, handler: H) {
        while (true) {
            val prev = state.load()
            val prior = prev[hook].orEmpty()
            val next = prev + (hook to prior + handler)
            if (state.compareAndSet(prev, next)) return
        }
    }

    override fun <H : Any> register(hook: Hook<H>, handler: H): HookRegistration {
        on(hook, handler)
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
