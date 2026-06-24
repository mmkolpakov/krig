package space.kscience.krig.core.contracts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import space.kscience.krig.core.InternalKrigApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Tracks in-flight operations for graceful shutdown coordination. */
@InternalKrigApi
public interface OperationTracker {
    public fun enterOperation()
    public fun exitOperation()
}

/**
 * Coroutine-scoped set of trackers already counting the current call path. A decorator and the
 * device it wraps share the same tracker; without this record one logical operation would be
 * counted twice, inflating the in-flight count.
 */
@InternalKrigApi
public class OperationTrackingScope(public val trackers: Set<OperationTracker>) :
    AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<OperationTrackingScope>
}

/**
 * Counts [block] against this tracker exactly once per logical operation: if an outer call on the
 * same coroutine path already entered this exact tracker, the nested call runs without re-counting.
 * Different trackers (e.g. another device) are always counted independently.
 */
@InternalKrigApi
public suspend fun <T> OperationTracker.trackReentrant(block: suspend () -> T): T {
    val held = currentCoroutineContext()[OperationTrackingScope]?.trackers.orEmpty()
    if (this in held) return block()
    enterOperation()
    return try {
        withContext(OperationTrackingScope(held + this)) { block() }
    } finally {
        exitOperation()
    }
}
