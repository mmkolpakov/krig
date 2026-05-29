@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
)

package space.kscience.krig.core.timetravel

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.names.Name

/** Merge of [Device.controlFlow] and [Device.dataFlow]; the default source for [enableTimeTravel]. */
public fun Device.defaultTimeTravelMessageFlow(): Flow<DeviceMessageEnvelope<DeviceMessage>> =
    merge(controlFlow, dataFlow)

/**
 * Wires event-sourced time travel onto [this] device: every message on [messageFlow] is
 * recorded to [eventSink]; snapshots are captured into [snapshotStore] per [strategy].
 * Children run under a [SupervisorJob] so the pump and the checkpointer fail independently.
 * Cancel the returned [Job] to stop both.
 *
 * @param scope hosts both children; defaults to [Device.deviceScope]
 */
@ExperimentalTimeTravelApi
public fun <D : Device> D.enableTimeTravel(
    reconstructible: DeviceReconstructible<D>,
    deviceName: Name,
    snapshotStore: SnapshotStore,
    eventSink: ReplaySink,
    strategy: CheckpointStrategy,
    messageFlow: Flow<DeviceMessageEnvelope<DeviceMessage>> = defaultTimeTravelMessageFlow(),
    scope: CoroutineScope = this.deviceScope,
    clock: Clock = Clock.System,
    snapshotCodec: SnapshotCodec = SnapshotCodec(),
    retentionPolicy: SnapshotRetentionPolicy = SnapshotRetentionPolicy.keepAll,
): Job {
    val supervisor = SupervisorJob(parent = scope.coroutineContext.job)
    val supervisedScope = CoroutineScope(scope.coroutineContext + supervisor)

    messageFlow
        .onEach(eventSink::record)
        .launchIn(supervisedScope)

    @Suppress("RETURN_VALUE_NOT_USED")
    reconstructible.runCheckpointing(
        subject = deviceName,
        messageFlow = messageFlow,
        snapshotStore = snapshotStore,
        strategy = strategy,
        scope = supervisedScope,
        clock = clock,
        snapshotCodec = snapshotCodec,
        retentionPolicy = retentionPolicy,
    )

    return supervisor
}

/** Overload that allocates a fresh [InMemoryReplayLog] and returns it alongside the [Job]. */
@ExperimentalTimeTravelApi
public fun <D : Device> D.enableTimeTravel(
    reconstructible: DeviceReconstructible<D>,
    deviceName: Name,
    snapshotStore: SnapshotStore,
    strategy: CheckpointStrategy,
    messageFlow: Flow<DeviceMessageEnvelope<DeviceMessage>> = defaultTimeTravelMessageFlow(),
    scope: CoroutineScope = this.deviceScope,
    clock: Clock = Clock.System,
    snapshotCodec: SnapshotCodec = SnapshotCodec(),
    retentionPolicy: SnapshotRetentionPolicy = SnapshotRetentionPolicy.keepAll,
): Pair<Job, RecordingReplayLog> {
    val store = InMemoryReplayLog()
    val job = enableTimeTravel(
        reconstructible = reconstructible,
        deviceName = deviceName,
        snapshotStore = snapshotStore,
        eventSink = store,
        strategy = strategy,
        messageFlow = messageFlow,
        scope = scope,
        clock = clock,
        snapshotCodec = snapshotCodec,
        retentionPolicy = retentionPolicy,
    )
    return job to store
}

// ── convenience: device.withTimeTravel(...) one-liner ──

/**
 * Wires time-travel recording onto [this] device in one call — the returned
 * [Reconstructible] is backed by [replayLog] and [snapshotStore] so callers can
 * immediately call [Reconstructible.timeTravel] or [counterfactual].
 *
 * ```
 * val tt = device.withTimeTravel(replayLog, snapshotStore)
 * tt.timeTravel(at, replayLog)
 * tt.counterfactual(replayLog, at, snapshot) { event -> /* mutate */ }
 * ```
 */
@ExperimentalTimeTravelApi
public fun <D : Device> D.withTimeTravel(
    reconstructible: DeviceReconstructible<D>,
    replayLog: RecordingReplayLog,
    snapshotStore: SnapshotStore = InMemorySnapshotStore(),
    deviceName: Name = this.name,
    strategy: CheckpointStrategy = CheckpointStrategy.manual,
    scope: CoroutineScope = this.deviceScope,
    clock: Clock = Clock.System,
    snapshotCodec: SnapshotCodec = SnapshotCodec(),
    retentionPolicy: SnapshotRetentionPolicy = SnapshotRetentionPolicy.keepAll,
): Reconstructible {
    enableTimeTravel(
        reconstructible = reconstructible,
        deviceName = deviceName,
        snapshotStore = snapshotStore,
        eventSink = replayLog,
        strategy = strategy,
        messageFlow = defaultTimeTravelMessageFlow(),
        scope = scope,
        clock = clock,
        snapshotCodec = snapshotCodec,
        retentionPolicy = retentionPolicy,
    ).let { }
    return reconstructible
}
