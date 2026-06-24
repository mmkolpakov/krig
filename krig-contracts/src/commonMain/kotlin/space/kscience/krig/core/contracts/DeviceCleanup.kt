package space.kscience.krig.core.contracts

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.InternalKrigApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Device-level graceful shutdown contract. Implementations stop accepting new operations, wait for
 * in-flight tracked work up to the requested timeout, then shut down.
 *
 * Drain covers *tracked operations* only, not fire-and-forget coroutines launched into the device
 * scope — put hardware safe-state in the shutdown path, not in a pending background job.
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

/** Default budget for one device's stop step before it is abandoned to keep the parent responsive. */
@InternalKrigApi
public val DEFAULT_DEVICE_SHUTDOWN_TIMEOUT: Duration = 10.seconds

/** Reported when a device did not stop within its budget; its scope is abandoned rather than awaited. */
@InternalKrigApi
public class DeviceShutdownTimeoutException(deviceName: Name, timeout: Duration) :
    Exception("Device '$deviceName' did not stop within $timeout; abandoned to keep the parent responsive")

/**
 * Runs [stop] under [timeout] so a hung child never blocks the parent. On timeout (or cooperative
 * cancellation that [stop] honours) the failure is reported and a non-suspending [Device.close] is
 * attempted as a last resort. A truly non-cooperative blocking call cannot be force-killed in
 * coroutines — drivers must keep `shutdown()` cancellable (offload blocking I/O to a dispatcher).
 */
@InternalKrigApi
public suspend fun closeDeviceBounded(
    child: Device,
    timeout: Duration = DEFAULT_DEVICE_SHUTDOWN_TIMEOUT,
    stop: suspend () -> Unit,
) {
    val finished = withTimeoutOrNull(timeout) {
        try {
            stop()
        } catch (timeout: TimeoutCancellationException) {
            throw timeout // let withTimeoutOrNull report the timeout
        } catch (cancel: CancellationException) {
            // A real cancellation of our own scope must propagate; a cancellation thrown by the
            // child's cleanup must not abort the parent's shutdown — suppress only the latter.
            if (!currentCoroutineContext().isActive) throw cancel
            CleanupFailureReporting.report(cancel)
        } catch (e: Exception) {
            CleanupFailureReporting.report(e)
        }
        true
    }
    if (finished == null) {
        CleanupFailureReporting.report(DeviceShutdownTimeoutException(child.name, timeout))
        ignoreNonCancellationFailure { child.close() }
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
