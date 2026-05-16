package space.kscience.krig.core.operations

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/** Supervisor scope: one failing child does not cancel siblings, parent cancellation still propagates. */
public fun deviceScope(
    parentContext: CoroutineContext,
    onError: (Throwable) -> Unit = {},
): CoroutineScope {
    val handler = CoroutineExceptionHandler { _, throwable -> onError(throwable) }
    return CoroutineScope(parentContext + SupervisorJob(parentContext[Job]) + handler)
}

/** Context-parameter overload inheriting from the enclosing [CoroutineScope]. */
context(scope: CoroutineScope)
public fun deviceScope(
    onError: (Throwable) -> Unit = {},
): CoroutineScope = deviceScope(scope.coroutineContext, onError)
