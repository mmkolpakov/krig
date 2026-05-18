package space.kscience.krig.core.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.hub.DynamicHub
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.Device
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Suspends until this hub's current child-name set equals [expectedNames].
 *
 * This is a small DX helper for control loops, tests, and demos that need to wait for
 * asynchronous topology convergence without depending on dispatcher-specific `yield`
 * behavior. For custom predicates, collect [Device.childrenFlow] directly.
 */
public suspend fun DynamicHub.awaitChildren(
    expectedNames: Set<Name>,
    timeout: Duration = 5.seconds,
): Map<Name, Device> = withTimeout(timeout) {
    childrenFlow.first { children -> children.keys == expectedNames }
}

/**
 * Handle on a running reconcile loop. [job] ties the loop's lifetime to the scope; [events]
 * surfaces per-operation outcomes for observability (attach success/failure, detach
 * success/failure). Active subscribers observe a bounded best-effort stream; old
 * reconcile events may be dropped on observer stalls. Current topology lives in [DynamicHub.children] and
 * [Device.childrenFlow]. Cancelling [job] stops collection but does NOT detach already-attached
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

    internal suspend fun rollback() {
        rollbacks.asReversed().forEach { cleanup ->
            ignoreRollbackFailure { cleanup() }
        }
    }
}

private suspend inline fun ignoreRollbackFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Rollback is best-effort; the original production/attach fault is the signal.
    }
}

/**
 * Reconciliation loop for a [DynamicHub]. Device construction is concurrent; [DynamicHub.attachAll]
 * is required to transfer ownership atomically. Errors surface via [ReconcileLoop.events],
 * never throw.
 *
 * Default `scope = context`; pass `CoroutineScope(context + Job())` to isolate the
 * reconciler's lifetime. Cancelling the returned [ReconcileLoop.job] stops the loop
 * but does not detach already-attached children.
 */
context(context: Context)
public fun DynamicHub.reconcile(
    desired: Flow<Set<Name>>,
    produce: suspend (Name) -> Device,
    scope: CoroutineScope = context,
    productionTimeout: Duration? = null,
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
 */
context(context: Context)
public fun DynamicHub.reconcileScoped(
    desired: Flow<Set<Name>>,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    scope: CoroutineScope = context,
    productionTimeout: Duration? = null,
): ReconcileLoop {
    val events = MutableSharedFlow<ReconcileEvent>(
        extraBufferCapacity = RECONCILE_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val job = scope.launch {
        desired.distinctUntilChanged().collectLatest { desiredNames ->
            val currentNames = children.keys
            val toAttach = desiredNames - currentNames
            val toDetach = currentNames - desiredNames

            if (toAttach.isNotEmpty()) {
                supervisorScope {
                    val results = toAttach.map { name ->
                        async { produceForReconcile(name, produce, productionTimeout) }
                    }.awaitAll()
                    val produced = mutableListOf<ProducedDevice>()
                    results.forEach { result ->
                        when (result) {
                            is ProducedDevice -> produced += result
                            is FailedDeviceProduction ->
                                events.tryEmit(ReconcileEvent.AttachFailed(result.name, result.cause))
                        }
                    }
                    val deviceMap = produced.associate { it.name to it.device }
                    if (deviceMap.isNotEmpty()) {
                        var transferred = false
                        try {
                            attachAll(deviceMap)
                            transferred = true
                            deviceMap.forEach { (name, device) ->
                                events.tryEmit(ReconcileEvent.Attached(name, device))
                            }
                        } catch (e: Throwable) {
                            if (!transferred) {
                                produced.forEach { it.rollback() }
                            }
                            if (e is CancellationException) throw e
                            if (e is Error) throw e
                            deviceMap.forEach { (name, _) ->
                                events.tryEmit(ReconcileEvent.AttachFailed(name, e))
                            }
                        }
                    }
                }
            }

            if (toDetach.isNotEmpty()) {
                // Parallel detach with per-child isolation — a hanging or slow `device.close()`
                // on one child never blocks the others. Mirrors the attach path above.
                supervisorScope {
                    toDetach.map { name ->
                        async {
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
                    }.awaitAll()
                }
            }
        }
    }
    return ReconcileLoop(job = job, events = events.asSharedFlow())
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
 * context-parameter version via `context(context)`.
 */
public fun DynamicHub.reconcileScoped(
    context: Context,
    desired: Flow<Set<Name>>,
    produce: suspend ReconcileProductionScope.(Name) -> Device,
    scope: CoroutineScope = context,
    productionTimeout: Duration? = null,
): ReconcileLoop = context(context) {
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
 * context-parameter version via `context(context)`.
 */
public fun DynamicHub.reconcile(
    context: Context,
    desired: Flow<Set<Name>>,
    produce: suspend (Name) -> Device,
    scope: CoroutineScope = context,
    productionTimeout: Duration? = null,
): ReconcileLoop = context(context) {
    reconcile(
        desired = desired,
        produce = produce,
        scope = scope,
        productionTimeout = productionTimeout,
    )
}

private const val RECONCILE_EVENT_BUFFER_CAPACITY: Int = 1024
