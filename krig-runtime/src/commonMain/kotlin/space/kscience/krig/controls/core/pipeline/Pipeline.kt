@file:OptIn(InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.api.spec.ResourceLockSpec
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
internal suspend fun <R> acquireAllLocks(
    registry: ResourceLockRegistry,
    locks: List<ResourceLockSpec>,
    block: suspend () -> R,
): R {
    if (locks.isEmpty()) return block()
    val ordered = locks.canonicalizeLocks()
    return acquireRecursively(registry, ordered, 0, block)
}

private fun List<ResourceLockSpec>.canonicalizeLocks(): List<ResourceLockSpec> =
    distinctBy { it.resourceName }
        .sortedBy { it.resourceName.toString() }

@OptIn(InternalKrigApi::class)
private suspend fun <R> acquireRecursively(
    registry: ResourceLockRegistry,
    ordered: List<ResourceLockSpec>,
    index: Int,
    block: suspend () -> R,
): R {
    if (index == ordered.size) return block()
    val mutex = registry.mutexFor(ordered[index].resourceName)
    return mutex.withLock { acquireRecursively(registry, ordered, index + 1, block) }
}

/** Retry wrapper for IO faults. */
internal suspend fun <R> withIoRetry(retry: RetryPolicy?, block: suspend () -> R): R {
    val policy = retry ?: return block()
    if (policy.maxAttempts <= 0) return block()
    var attempts = 0
    var latest: DeviceFaultException? = null
    var nextDelay = policy.initialDelay
    while (attempts <= policy.maxAttempts) {
        try { return block() }
        catch (e: DeviceFaultException) {
            latest = e
            attempts++
            if (policy.maxAttempts < attempts) break
            if (nextDelay > Duration.ZERO) delay(nextDelay)
            nextDelay = nextRetryDelay(nextDelay, policy)
        }
    }
    throw latest ?: error("withIoRetry: bug — exited without fault")
}

internal suspend fun <R> withIoRetry(retry: Int, block: suspend () -> R): R =
    withIoRetry(RetryPolicy(maxAttempts = retry), block)

private fun nextRetryDelay(current: Duration, policy: RetryPolicy): Duration {
    if (current == Duration.ZERO || policy.backoffMultiplier == 1.0) return current
    val candidate = current * policy.backoffMultiplier
    return if (candidate > policy.maxDelay) policy.maxDelay else candidate
}

private suspend fun <R> runWithTimeout(timeout: Duration?, block: suspend () -> R): R =
    if (timeout == null) block()
    else try { withTimeout(timeout) { block() } }
         catch (_: TimeoutCancellationException) { throw DeviceFaultException(TimeoutFault()) }

internal suspend fun <R> withGlobalTimeout(timeout: Duration?, block: suspend () -> R): R =
    runWithTimeout(timeout, block)

internal suspend fun <R> withResilience(timeout: Duration?, retry: Int, block: suspend () -> R): R =
    withGlobalTimeout(timeout) { withIoRetry(retry, block) }

internal suspend fun <R> withResilience(timeout: Duration?, retry: RetryPolicy?, block: suspend () -> R): R =
    withGlobalTimeout(timeout) { withIoRetry(retry, block) }

/** Timing wrapper: captures duration + fault for observers. */
internal fun <I, O> Pipeline<I, O>.wrapWithTiming(
    observers: suspend (durationNanos: Long, fault: DeviceFault?) -> Unit,
) {
    prepend { input, next ->
        val mark = TimeSource.Monotonic.markNow()
        var captured: DeviceFault? = null
        try { next(input) }
        catch (e: DeviceFaultException) { captured = e.fault; throw e }
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
internal fun <I, O> Pipeline<I, O>.wrapWithGlobalTimeout(
    timeout: Duration?,
) {
    prepend { input, next -> withGlobalTimeout(timeout) { next(input) } }
}

/** Wraps only IO/driver work in retry semantics. */
internal fun <I, O> Pipeline<I, O>.wrapWithIoRetry(
    retry: Int,
) {
    prepend { input, next -> withIoRetry(retry) { next(input) } }
}

/** Wraps only IO/driver work in retry semantics. */
internal fun <I, O> Pipeline<I, O>.wrapWithIoRetry(
    retry: RetryPolicy?,
) {
    prepend { input, next -> withIoRetry(retry) { next(input) } }
}

/** Wraps timeout + retry. Prefer explicit [wrapWithGlobalTimeout] + [wrapWithIoRetry]. */
internal fun <I, O> Pipeline<I, O>.wrapWithResilience(
    timeout: Duration?,
    retry: Int,
): Unit {
    prepend { input, next -> withResilience(timeout, retry) { next(input) } }
}

/** Wraps timeout + retry. Prefer explicit [wrapWithGlobalTimeout] + [wrapWithIoRetry]. */
internal fun <I, O> Pipeline<I, O>.wrapWithResilience(
    timeout: Duration?,
    retry: RetryPolicy?,
) {
    prepend { input, next -> withResilience(timeout, retry) { next(input) } }
}

internal fun <I, O> compileOperationExecutor(
    timeout: Duration?,
    retry: RetryPolicy?,
    gates: List<suspend () -> Unit>,
    registry: ResourceLockRegistry,
    locks: List<ResourceLockSpec>,
    timeSource: TimeSource = TimeSource.Monotonic,
    observers: suspend (durationNanos: Long, fault: DeviceFault?) -> Unit,
    terminal: suspend (I) -> O,
): suspend (I) -> O = { input ->
    val mark = timeSource.markNow()
    var captured: DeviceFault? = null
    try {
        withGlobalTimeout(timeout) {
            for (gate in gates) gate()
            withIoRetry(retry) {
                acquireAllLocks(registry, locks) { terminal(input) }
            }
        }
    } catch (e: DeviceFaultException) {
        captured = e.fault
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        captured = faultForThrowable(e)
        throw e
    } finally {
        ignoreObserverFailure { observers(mark.elapsedNow().inWholeNanoseconds, captured) }
    }
}

private fun faultForThrowable(e: Throwable): GenericDeviceFault =
    GenericDeviceFault(
        code = if (e is Exception) (e::class.simpleName ?: "SYSTEM_ERROR") else "FATAL_SYSTEM_ERROR",
        message = e.message ?: e.toString(),
    )

private suspend inline fun ignoreObserverFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
        // Observability must never change operation semantics.
    }
}
