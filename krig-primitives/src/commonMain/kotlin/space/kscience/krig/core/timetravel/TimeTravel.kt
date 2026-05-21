package space.kscience.krig.core.timetravel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.jvm.JvmInline
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * An ordered source of [DeviceMessage]s — replay-capable, mergeable, with a
 * [timeOf] extractor for sort–merge combinators. Wraps a [Flow] but guarantees
 * [events] belong to a single device; use [Timeline.merge] for multi-device.
 */
@JvmInline
public value class Timeline(public val events: Flow<DeviceMessage>) {
    /** Extract the logical time of [message] in this timeline (default: `message.time`). */
    public fun timeOf(message: DeviceMessage): Instant = message.time

    public companion object
}

/**
 * Merges this timeline with [other], interleaving events by [selector] (default chronological).
 * Each event retains its source timeline identity.
 */
public fun Timeline.merge(
    other: Timeline,
    selector: (DeviceMessage, DeviceMessage) -> Int = { a, b -> timeOf(a).compareTo(timeOf(b)) },
): Timeline = Timeline(flow {
    coroutineScope {
        val left = events.collectToChannel(this)
        val right = other.events.collectToChannel(this)
        try {
            var leftValue = left.receiveOrThrow()
            var rightValue = right.receiveOrThrow()
            while (leftValue != null && rightValue != null) {
                if (selector(leftValue, rightValue) <= 0) {
                    emit(leftValue)
                    leftValue = left.receiveOrThrow()
                } else {
                    emit(rightValue)
                    rightValue = right.receiveOrThrow()
                }
            }
            while (leftValue != null) {
                emit(leftValue)
                leftValue = left.receiveOrThrow()
            }
            while (rightValue != null) {
                emit(rightValue)
                rightValue = right.receiveOrThrow()
            }
        } finally {
            left.cancel()
            right.cancel()
        }
    }
})

private fun Flow<DeviceMessage>.collectToChannel(scope: CoroutineScope): ReceiveChannel<DeviceMessage> {
    val channel = Channel<DeviceMessage>(Channel.BUFFERED)
    scope.launch {
        try {
            collect { channel.send(it) }
            channel.close()
        } catch (e: Throwable) {
            channel.close(e)
        }
    }
    return channel
}

private suspend fun ReceiveChannel<DeviceMessage>.receiveOrThrow(): DeviceMessage? {
    val result = receiveCatching()
    result.exceptionOrNull()?.let { throw it }
    return result.getOrNull()
}

/**
 * Convenience: creates a [Timeline] from a [Device]'s message flow.
 * For persistent sources, use [DeviceEventLog].
 */
@OptIn(InternalKrigApi::class)
public fun Device.timeline(): Timeline = Timeline(messageFlow.payloads())

/**
 * Replay-capable ordered source of [DeviceMessage]s for a single device.
 * In-memory logs tail `device.messageFlow`; persistent ones load from storage.
 */
public fun interface DeviceEventLog {
    /** Cold flow of messages with timestamps in `[from, until]`. */
    public fun replay(from: Instant, until: Instant): Flow<DeviceMessage>
}

/**
 * Replay source with storage-assigned cursors. Required for deterministic branching:
 * database sequence IDs or offsets, not timestamps, define the exact log position.
 */
public interface CursorEventLog : DeviceEventLog {
    /** Cold flow of records after [after], or from the beginning when [after] is `null`. */
    public fun replayFrom(after: EventCursor? = null): Flow<EventRecord>

    /**
     * Cold flow of cursor records in `[from, until]`. Persistent stores should
     * override this method and seek through their time index instead of scanning
     * from the beginning.
     */
    public fun replayRecords(from: Instant, until: Instant): Flow<EventRecord> =
        replayFrom(null)
            .dropWhile { it.message.time < from }
            .takeWhile { it.message.time <= until }
}

/**
 * Storage-assigned cursor pointing to a specific position in the event log.
 * Persistent backends implement with sequence IDs, database offsets, or Kafka offsets.
 * Not to be confused with device-generated HLC timestamps — the cursor is assigned
 * by the store at write time.
 */
public interface EventCursor : Comparable<EventCursor>

/**
 * A [DeviceMessage] paired with its storage-assigned [cursor].
 * Returned by [CursorEventLog.replayFrom]; subscribers can store the cursor
 * to resume replay deterministically later.
 */
public data class EventRecord(
    val cursor: EventCursor,
    val message: DeviceMessage,
)

/** Wraps an unbounded [messages] flow as a [DeviceEventLog]. */
public fun DeviceEventLog(messages: Flow<DeviceMessage>): DeviceEventLog =
    DeviceEventLog { from, until ->
        messages
            .dropWhile { it.time < from }
            .takeWhile { it.time <= until }
    }

/**
 * Convenience: returns a [DeviceEventLog] that wraps this device's [Device.messageFlow].
 */
@OptIn(InternalKrigApi::class)
public fun Device.eventLog(): DeviceEventLog = DeviceEventLog(messageFlow.payloads())

/**
 * **Digital-twin role marker.** A state object whose state is a pure fold of a message log:
 * [applyEvent] advances deterministically, [captureSnapshot] / [restoreSnapshot] checkpoint
 * the fold. Real hardware cannot implement this; simulations, digital twins, and recording
 * wrappers opt in.
 *
 * Typed form [DeviceReconstructible] provides compile-time binding to a specific [Device] for
 * use with [enableTimeTravel].
 */
public interface Reconstructible {
    /** Advance state by [event]. Must be pure and idempotent. */
    public suspend fun applyEvent(event: DeviceMessage)

    /** Capture current state at [at]. */
    public suspend fun captureSnapshot(at: Instant): DeviceSnapshot

    /** Restore state from [snapshot]. Subsequent [applyEvent] continues the fold. */
    public suspend fun restoreSnapshot(snapshot: DeviceSnapshot)
}

/**
 * Typed [Reconstructible] bound to a specific [Device] type [D]. Provides compile-time safety
 * for [enableTimeTravel] — the reconstructible is guaranteed to be compatible with the device
 * whose message flow it folds.
 */
public interface DeviceReconstructible<D : Device> : Reconstructible

/**
 * Replays [log] over an optional baseline [snapshot] to reach state at [at]. With a `null`
 * snapshot and a long audit log, replay starts from `Instant.DISTANT_PAST` — production
 * callers use the [SnapshotStore]-aware overload.
 */
public suspend fun Reconstructible.timeTravel(
    at: Instant,
    log: DeviceEventLog,
    snapshot: DeviceSnapshot? = null,
) {
    val from = snapshot?.at ?: Instant.DISTANT_PAST
    if (snapshot != null) restoreSnapshot(snapshot)
    log.replay(from, at).collect { applyEvent(it) }
}

/**
 * Smart-resolving variant: asks [snapshotStore] for the nearest snapshot before [at] and
 * replays [log] from there. If no snapshot exists, falls back to full replay — behaviour
 * identical to the nullable-snapshot overload, but the call site reads as intent.
 */
public suspend fun Reconstructible.timeTravel(
    at: Instant,
    log: DeviceEventLog,
    deviceName: Name,
    snapshotStore: SnapshotStore,
) {
    val snapshot = snapshotStore.latestBefore(deviceName, at)
    timeTravel(at, log, snapshot)
}
