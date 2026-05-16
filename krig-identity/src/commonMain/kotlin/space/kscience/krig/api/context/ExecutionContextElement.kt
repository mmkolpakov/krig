package space.kscience.krig.api.context

import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying [ExecutionContext] for opt-in ambient access.
 * Use [executionContext] extension property for ergonomic retrieval.
 */
public class ExecutionContextElement(
    public val context: ExecutionContext,
) : CoroutineContext.Element {
    public companion object Key : CoroutineContext.Key<ExecutionContextElement>
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Retrieves the ambient [ExecutionContext] from the current coroutine context, or null if not set.
 */
public val CoroutineContext.executionContext: ExecutionContext?
    get() = this[ExecutionContextElement]?.context

/**
 * Creates a [CoroutineContext] containing the given [ExecutionContext].
 * Use with `withContext(executionContextOf(ctx)) { ... }` in code that depends on kotlinx.coroutines.
 */
public fun executionContextOf(context: ExecutionContext): CoroutineContext = ExecutionContextElement(context)
