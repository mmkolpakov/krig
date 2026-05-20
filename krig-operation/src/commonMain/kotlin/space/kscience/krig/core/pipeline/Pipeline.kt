@file:OptIn(InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.FaultRetryClassifier
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.faultOrNull
import space.kscience.krig.api.result.ok
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.operations.ResourceLockRegistry
import kotlin.time.Duration

/**
 * Composable middleware pipeline — a fixed-order chain of interceptors compiled
 * once at reader/writer/action creation time. Each interceptor wraps the next call;
 * the hot path invokes the pre-built chain with zero GC pressure.
 *
 * Analogue of Ktor's pipeline model, minimalised for krig.
 */
public class Pipeline<I, O> {
    private val interceptors: MutableList<suspend (I, suspend (I) -> O) -> O> = mutableListOf()

    /** Concatenates [interceptor] at the start of the chain (outermost wrapper). */
    public fun prepend(interceptor: suspend (I, suspend (I) -> O) -> O) {
        interceptors.add(0, interceptor)
    }

    /** Builds the compiled executor — call once, reuse forever. */
    public fun build(terminal: suspend (I) -> O): suspend (I) -> O =
        interceptors.foldRight(terminal) { interceptor, next ->
            { input -> interceptor(input, next) }
        }
}

/**
 * Locks each resource once in deterministic order. Resource locks are exclusive;
 * duplicate resource names are merged before acquisition.
 */
@OptIn(InternalKrigApi::class)
@InternalKrigApi
public suspend fun <R> acquireAllLocks(
    registry: ResourceLockRegistry,
    locks: List<ResourceLock>,
    block: suspend () -> R,
): R {
    if (locks.isEmpty()) return block()
    val ordered = locks.canonicalizeLocks()
    return acquireRecursively(registry, ordered, 0, block)
}

private fun List<ResourceLock>.canonicalizeLocks(): List<ResourceLock> =
    distinctBy { it.resourceName }
        .sortedBy { it.resourceName.toString() }

@OptIn(InternalKrigApi::class)
private suspend fun <R> acquireRecursively(
    registry: ResourceLockRegistry,
    ordered: List<ResourceLock>,
    index: Int,
    block: suspend () -> R,
): R {
    if (index == ordered.size) return block()
    val mutex = registry.mutexFor(ordered[index].resourceName)
    return mutex.withLock { acquireRecursively(registry, ordered, index + 1, block) }
}

/** Retry wrapper for predictable transient faults. */
@InternalKrigApi
public suspend fun <R> withIoRetry(
    retry: RetryPolicy?,
    retryClassifier: FaultRetryClassifier = FaultRetryClassifier.Default,
    block: suspend () -> OperationOutcome<R>,
): OperationOutcome<R> {
    val policy = retry ?: return block()
    if (policy.maxAttempts <= 0) return block()
    var retries = 0
    var nextDelay = policy.initialDelay

    while (true) {
        val result = block()
        val fault = result.faultOrNull() ?: return result
        if (!retryClassifier.shouldRetry(fault) || retries >= policy.maxAttempts) return result
        if (nextDelay > Duration.ZERO) delay(nextDelay)
        retries++
        nextDelay = nextRetryDelay(nextDelay, policy)
    }
}

@InternalKrigApi
public suspend fun <R> catchingOperationOutcome(block: suspend () -> R): OperationOutcome<R> =
    try {
        ok(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: OperationFaultException) {
        OperationOutcome.Fail(e.fault)
    } catch (e: kotlinx.io.IOException) {
        OperationOutcome.Fail(
            TransportFault(
                causeType = e::class.simpleName ?: "IOException",
                message = e.message ?: "I/O error",
            ),
        )
    }

@InternalKrigApi
public suspend fun <R> withGlobalTimeout(
    timeout: Duration?,
    block: suspend () -> OperationOutcome<R>,
): OperationOutcome<R> =
    if (timeout == null) {
        block()
    } else {
        try {
            withTimeout(timeout) { block() }
        } catch (_: TimeoutCancellationException) {
            OperationOutcome.Fail(TimeoutFault())
        }
    }

private fun nextRetryDelay(current: Duration, policy: RetryPolicy): Duration {
    if (current == Duration.ZERO || policy.backoffMultiplier == 1.0) return current
    val candidate = current * policy.backoffMultiplier
    return if (candidate > policy.maxDelay) policy.maxDelay else candidate
}

/** Timing wrapper: captures duration + fault for observers. */
@InternalKrigApi
public fun <I, O> Pipeline<I, OperationOutcome<O>>.wrapWithTiming(
    observers: suspend (durationNanos: Long, fault: OperationFault?) -> Unit,
) {
    prepend { input, next ->
        val mark = TimeSource.Monotonic.markNow()
        var captured: OperationFault? = null
        try {
            val result = next(input)
            captured = result.faultOrNull()
            result
        }
        catch (e: OperationFaultException) {
            captured = e.fault
            OperationOutcome.Fail(e.fault)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) {
            captured = faultForThrowable(e)
            throw e
        }
        finally {
            ignoreObserverFailure { observers(mark.elapsedNow().inWholeNanoseconds, captured) }
        }
    }
}

/** Wraps the complete operation in one timeout budget. */
@InternalKrigApi
public fun <I, O> Pipeline<I, OperationOutcome<O>>.wrapWithGlobalTimeout(
    timeout: Duration?,
) {
    prepend { input, next -> withGlobalTimeout(timeout) { next(input) } }
}

/** Wraps only IO/driver work in retry semantics. */
@InternalKrigApi
public fun <I, O> Pipeline<I, OperationOutcome<O>>.wrapWithIoRetry(
    retry: RetryPolicy?,
) {
    prepend { input, next -> withIoRetry(retry) { next(input) } }
}

@InternalKrigApi
public fun <I, O> compileOperationExecutor(
    timeout: Duration?,
    retry: RetryPolicy?,
    gates: List<suspend () -> OperationOutcome<Unit>>,
    registry: ResourceLockRegistry,
    locks: List<ResourceLock>,
    timeSource: TimeSource = TimeSource.Monotonic,
    observers: suspend (durationNanos: Long, fault: OperationFault?) -> Unit,
    terminal: suspend (I) -> OperationOutcome<O>,
): suspend (I) -> OperationOutcome<O> = { input ->
    val mark = timeSource.markNow()
    var captured: OperationFault? = null
    try {
        val result = withGlobalTimeout(timeout) {
            for (gate in gates) {
                val gateResult = gate()
                if (gateResult is OperationOutcome.Fail) return@withGlobalTimeout gateResult
            }
            withIoRetry(retry) {
                acquireAllLocks(registry, locks) { terminal(input) }
            }
        }
        captured = result.faultOrNull()
        result
    } catch (e: OperationFaultException) {
        captured = e.fault
        OperationOutcome.Fail(e.fault)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        captured = faultForThrowable(e)
        throw e
    } finally {
        ignoreObserverFailure { observers(mark.elapsedNow().inWholeNanoseconds, captured) }
    }
}

private fun faultForThrowable(e: Throwable): GenericOperationFault =
    GenericOperationFault(
        faultType = if (e is Exception) OperationFaultTypes.System else OperationFaultTypes.FatalSystem,
        message = e.message ?: e.toString(),
    )

private suspend inline fun ignoreObserverFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
        // Observability must never change operation semantics.
    }
}
