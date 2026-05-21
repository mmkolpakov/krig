package space.kscience.krig.core.contracts

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.InternalKrigApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Device-level graceful shutdown contract. Implementations stop accepting new
 * operations, wait for in-flight work up to the requested timeout, then shut down.
 */
public interface GracefullyCloseable {
    public suspend fun closeGracefully(drainTimeout: Duration)
}

/** Receives best-effort cleanup failures that are intentionally suppressed. */
@InternalKrigApi
public fun interface CleanupFailureReporter {
    public fun report(failure: Exception)
}

/**
 * Process-local reporting hook for cleanup failures. Shutdown cleanup remains best-effort,
 * but tests and hosts may install a reporter to keep diagnostics visible.
 */
@InternalKrigApi
public object CleanupFailureReporting {
    private val reporterRef = atomic<CleanupFailureReporter?>(null)

    public fun install(reporter: CleanupFailureReporter?) {
        reporterRef.value = reporter
    }

    public fun report(failure: Exception) {
        reporterRef.value?.report(failure)
    }
}

/**
 * Marker inherited by coroutines launched in a device-owned scope.
 */
@InternalKrigApi
public class DeviceScopeElement(public val deviceName: Name) : CoroutineContext.Element {
    public companion object Key : CoroutineContext.Key<DeviceScopeElement>

    override val key: CoroutineContext.Key<DeviceScopeElement> get() = Key
}

/** Runs best-effort cleanup while preserving coroutine cancellation. */
@InternalKrigApi
public inline fun ignoreNonCancellationFailure(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CleanupFailureReporting.report(e)
        // Best-effort cleanup path: ordinary failures are intentionally ignored.
    }
}

/** Suspended variant of [ignoreNonCancellationFailure]. */
@InternalKrigApi
public suspend inline fun ignoreNonCancellationFailureSuspending(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CleanupFailureReporting.report(e)
        // Best-effort cleanup path: ordinary failures are intentionally ignored.
    }
}

/**
 * Runs suspending cleanup in [NonCancellable] and suppresses every cleanup failure.
 *
 * Use only for shutdown/finally paths after ownership has already moved away from the
 * caller. It guarantees best-effort resource release even when the parent scope is
 * cancelling; normal operation paths should keep using [ignoreNonCancellationFailureSuspending].
 */
@InternalKrigApi
public suspend inline fun ignoreCleanupFailureSuspending(crossinline block: suspend () -> Unit) {
    try {
        withContext(NonCancellable) {
            block()
        }
    } catch (e: Exception) {
        CleanupFailureReporting.report(e)
        // Cleanup is best-effort; callers are already leaving the ownership scope.
    }
}

/**
 * Cancels [deviceScope] without joining it from one of its own children.
 *
 * A remote `shutdown` command may execute inside the device's operation scope. Joining the
 * root job from that child waits for the child itself and deadlocks. In that case we request
 * root cancellation after the current operation completes.
 */
@InternalKrigApi
public suspend fun cancelDeviceScopeSafely(deviceName: Name, deviceScope: CoroutineScope) {
    val deviceJob = deviceScope.coroutineContext[Job]
    val cause = CancellationException("Device '$deviceName' shutdown")
    if (deviceJob == null) {
        deviceScope.cancel(cause)
        return
    }

    val currentDevice = currentCoroutineContext()[DeviceScopeElement]?.deviceName
    if (currentDevice == deviceName) {
        val currentJob = currentCoroutineContext()[Job]
        currentJob?.invokeOnCompletion {
            deviceScope.cancel(cause)
        } ?: deviceScope.cancel(cause)
    } else {
        deviceJob.cancel(cause)
        deviceJob.join()
    }
}
