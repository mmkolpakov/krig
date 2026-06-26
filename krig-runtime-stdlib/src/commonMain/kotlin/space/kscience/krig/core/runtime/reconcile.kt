@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.hub.DeviceHub
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.CleanupFailureReporting
import space.kscience.krig.core.contracts.CleanupTimeoutException
import space.kscience.krig.core.contracts.DEFAULT_DEVICE_SHUTDOWN_TIMEOUT
import space.kscience.krig.core.contracts.Device
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Suspends until this hub's current child-name set equals [expectedNames].
 *
 * Helper for control loops, tests, and demos that wait for asynchronous topology
 * convergence without dispatcher-specific `yield` behavior. For custom predicates,
 * collect [DeviceHub.devicesFlow] directly.
 */
public suspend fun DeviceHub.awaitChildren(
    expectedNames: Set<Name>,
    timeout: Duration = 5.seconds,
): Map<Name, Device> = withTimeout(timeout) {
    devicesFlow.first { children -> children.keys == expectedNames }
}

/**
 * Default per-device production timeout for [reconcile]/[reconcileScoped]. A stuck `produce` call
 * fails just that device (rolling back its partial resources via [ReconcileProductionScope]) instead
 * of stalling the whole loop indefinitely. Pass `productionTimeout = null` to opt out explicitly.
 */
public val RECONCILE_DEFAULT_PRODUCTION_TIMEOUT: Duration = 30.seconds

/** Default budget for one rollback cleanup block in [reconcileScoped]. */
internal val RECONCILE_DEFAULT_ROLLBACK_TIMEOUT: Duration = DEFAULT_DEVICE_SHUTDOWN_TIMEOUT

/**
 * Handle on a running reconcile loop. [job] ties the loop's lifetime to the scope; [events]
 * surfaces per-operation outcomes for observability (attach success/failure, detach
 * success/failure). Active subscribers observe a bounded best-effort stream; old
 * reconcile events may be dropped on observer stalls. Current topology lives in [DeviceHub.devices]
 * and [DeviceHub.devicesFlow]. Cancelling [job] stops collection but does NOT detach already-attached
 * children.
 */
public class ReconcileLoop internal constructor(
    public val job: Job,
    public val events: Flow<ReconcileEvent>,
)

/** Reconcile outcome event, one per attach/detach attempt. */
public sealed interface ReconcileEvent {
    public val name: Name

    public data class Attached(override val name: Name, val device: Device) : ReconcileEvent
    public data class AttachFailed(override val name: Name, val cause: Throwable) : ReconcileEvent
    public data class Detached(override val name: Name) : ReconcileEvent
    public data class DetachFailed(override val name: Name, val cause: Throwable) : ReconcileEvent
}

/**
 * Construction scope for dynamic reconciliation.
 *
 * Producers that allocate intermediate resources before returning a [Device] should register
 * rollback cleanups here. The reconciler runs these cleanups if construction fails, times out,
 * or if the produced device is not transferred to the hub.
 */
public class ReconcileProductionScope internal constructor() {
    private val rollbacks = mutableListOf<suspend () -> Unit>()

    public fun onRollback(cleanup: suspend () -> Unit) {
        rollbacks += cleanup
    }

    internal suspend fun rollback(timeout: Duration = RECONCILE_DEFAULT_ROLLBACK_TIMEOUT) {
        rollbacks.asReversed().forEach { cleanup ->
            ignoreRollbackFailure(timeout) { cleanup() }
        }
    }
}

private suspend inline fun ignoreRollbackFailure(
    timeout: Duration = RECONCILE_DEFAULT_ROLLBACK_TIMEOUT,
    crossinline block: suspend () -> Unit,
) {
    try {
        // NonCancellable: rollback must release resources fully even if the reconciler is being
        // cancelled mid-attach; the local timeout keeps cooperative cleanup from hanging forever.
        withContext(NonCancellable) {
            val finished = withTimeoutOrNull(timeout) {
                block()
                true
            }
            if (finished == null) {
                CleanupFailureReporting.report(CleanupTimeoutException(timeout))
            }
        }
    } catch (e: Exception) {
        CleanupFailureReporting.report(e)
        // Rollback is best-effort; the original production/attach fault is the signal.
    }
}

/**
 * Reconciliation loop for a [DeviceHub]. Device construction is concurrent; [DeviceHub.attachAll]
 * is required to transfer ownership atomically. Errors surface via [ReconcileLoop.events],
 * never throw.
 *
 * Default `scope = context`; pass `CoroutineScope(context + Job())` to isolate the
 * reconciler's lifetime. Cancelling the returned [ReconcileLoop.job] stops the loop
 * but does not detach already-attached children.
 *
 * [productionTimeout] defaults to [RECONCILE_DEFAULT_PRODUCTION_TIMEOUT] so a hung producer cannot
 * stall the loop; pass `null` to disable the timeout.
 */
context(dataforgeContext: Context)
public fun DeviceHub.reconcile(
    desired: Flow<Set<Name>>,
    produce: suspend (Name) -> Device,
    scope: CoroutineScope = dataforgeContext,
    productionTimeout: Duration? = RECONCILE_DEFAULT_PRODUCTION_TIMEOUT,
): ReconcileLoop = reconcileScoped(
    desired = desired,
    produce = { name -> produce(name) },
    scope = scope,
    productionTimeout = productionTimeout,
)

/**
 * Scoped variant of [reconcile] for producers that need bracket-style rollback during
 * construction. Use [ReconcileProductionScope.onRollback] for sockets, transports, or
 * dispatchers created before the final [Device] instance is returned.
 *
 * [productionTimeout] defaults to [RECONCILE_DEFAULT_PRODUCTION_TIMEOUT]; pass `null` to disable it.
 */
context(dataforgeContext: Context)
public fun DeviceHub.reconcileScoped(
    desired: Flow<Set<Name>>,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    scope: CoroutineScope = dataforgeContext,
    productionTimeout: Duration? = RECONCILE_DEFAULT_PRODUCTION_TIMEOUT,
): ReconcileLoop {
    val events = MutableSharedFlow<ReconcileEvent>(
        extraBufferCapacity = RECONCILE_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val job = scope.launch {
        // In-flight attach productions keyed by name. Using `collect` (not `collectLatest`) plus this
        // map means a new `desired` never cancels a production that is still wanted — only the genuine
        // delta is launched, and only productions for no-longer-desired names are cancelled. This kills
        // the create/cancel thrashing of `collectLatest` under rapid `desired` churn.
        val inflightAttach = mutableMapOf<Name, Job>()
        desired.distinctUntilChanged().collect { desiredNames ->
            // Drop completed productions so a previously-failed name becomes eligible to retry.
            inflightAttach.entries.retainAll { !it.value.isCompleted }
            // Cancel in-flight productions for names no longer desired (genuinely surplus work).
            (inflightAttach.keys - desiredNames).toList().forEach { name ->
                inflightAttach.remove(name)?.cancel()
            }

            val currentNames = devices.keys
            // Launch attaches only for the delta: desired, not yet attached, not already in flight.
            val toAttach = desiredNames - currentNames - inflightAttach.keys
            toAttach.forEach { name ->
                inflightAttach[name] = launch { attachOneForReconcile(name, produce, productionTimeout, events) }
            }

            // Detach attached children no longer desired (parallel, per-child isolated).
            val toDetach = currentNames - desiredNames
            toDetach.forEach { name ->
                launch {
                    try {
                        if (detach(name, DeviceDepartureReason.Evicted) != null) {
                            events.tryEmit(ReconcileEvent.Detached(name))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        events.tryEmit(ReconcileEvent.DetachFailed(name, e))
                    }
                }
            }
        }
    }
    return ReconcileLoop(job = job, events = events.asSharedFlow())
}

/**
 * Produces one device and atomically transfers it to this hub, emitting the matching
 * [ReconcileEvent]. On any non-cancellation failure the partial production is rolled back and an
 * [ReconcileEvent.AttachFailed] is emitted; cancellation (the name became undesired) rolls back and
 * propagates so the in-flight job ends cleanly.
 */
private suspend fun DeviceHub.attachOneForReconcile(
    name: Name,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    productionTimeout: Duration?,
    events: MutableSharedFlow<ReconcileEvent>,
) {
    when (val result = produceForReconcile(name, produce, productionTimeout)) {
        is ProducedDevice -> {
            try {
                attachAll(mapOf(name to result.device))
                events.tryEmit(ReconcileEvent.Attached(name, result.device))
            } catch (e: CancellationException) {
                result.rollback()
                throw e
            } catch (e: Error) {
                result.rollback()
                throw e
            } catch (e: Throwable) {
                result.rollback()
                events.tryEmit(ReconcileEvent.AttachFailed(name, e))
            }
        }

        is FailedDeviceProduction -> events.tryEmit(ReconcileEvent.AttachFailed(name, result.cause))
    }
}

private sealed interface DeviceProductionResult {
    val name: Name
}

private data class ProducedDevice(
    override val name: Name,
    val device: Device,
    val rollback: suspend () -> Unit,
) : DeviceProductionResult

private data class FailedDeviceProduction(
    override val name: Name,
    val cause: Exception,
) : DeviceProductionResult

private suspend fun produceForReconcile(
    name: Name,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    productionTimeout: Duration?,
): DeviceProductionResult {
    val productionScope = ReconcileProductionScope()
    return try {
        val device = if (productionTimeout == null) {
            produce(productionScope, name)
        } else {
            withTimeout(productionTimeout) { produce(productionScope, name) }
        }
        productionScope.onRollback { device.shutdown() }
        ProducedDevice(name, device) { productionScope.rollback() }
    } catch (e: TimeoutCancellationException) {
        productionScope.rollback()
        FailedDeviceProduction(name, e)
    } catch (e: CancellationException) {
        productionScope.rollback()
        throw e
    } catch (e: Exception) {
        productionScope.rollback()
        FailedDeviceProduction(name, e)
    }
}

/**
 * Explicit-parameter overload of [reconcileScoped] for call sites where no [Context] is
 * ambient (e.g. `runBlocking {}`, simple `main()` demos). Delegates to the
 * context-parameter version via `context(dataforgeContext)`.
 */
public fun DeviceHub.reconcileScoped(
    dataforgeContext: Context,
    desired: Flow<Set<Name>>,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    scope: CoroutineScope = dataforgeContext,
    productionTimeout: Duration? = null,
): ReconcileLoop = context(dataforgeContext) {
    reconcileScoped(
        desired = desired,
        produce = produce,
        scope = scope,
        productionTimeout = productionTimeout,
    )
}

/**
 * Explicit-parameter overload of [reconcile] for call sites where no [Context] is
 * ambient (e.g. `runBlocking {}`, simple `main()` demos). Delegates to the
 * context-parameter version via `context(dataforgeContext)`.
 */
public fun DeviceHub.reconcile(
    dataforgeContext: Context,
    desired: Flow<Set<Name>>,
    produce: suspend (Name) -> Device,
    scope: CoroutineScope = dataforgeContext,
    productionTimeout: Duration? = null,
): ReconcileLoop = context(dataforgeContext) {
    reconcile(
        desired = desired,
        produce = produce,
        scope = scope,
        productionTimeout = productionTimeout,
    )
}

private const val RECONCILE_EVENT_BUFFER_CAPACITY: Int = 1024
