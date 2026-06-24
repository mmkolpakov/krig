package space.kscience.krig.storage.journal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.takeWhile
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Storage-assigned cursor pointing to a position in the event log. Persistent backends implement it
 * with sequence IDs, database offsets, or Kafka offsets. Not a device HLC timestamp — the cursor is
 * assigned by the store at write time, which is what makes branching deterministic.
 */
public interface EventCursor : Comparable<EventCursor>

/** Monotonic sequence cursor assigned by an [EventJournal] when an event is written. */
@JvmInline
public value class SequenceCursor(public val sequence: Long) : EventCursor {
    override fun compareTo(other: EventCursor): Int {
        require(other is SequenceCursor) {
            "SequenceCursor is comparable only with SequenceCursor, got ${other::class.simpleName}: " +
                    "cursors from different journal backends have no common order."
        }
        return sequence.compareTo(other.sequence)
    }
}

/**
 * A decoded envelope paired with its storage-assigned [cursor]. Subscribers can persist the cursor
 * to resume replay deterministically later.
 */
public data class ReplayRecord(
    public val cursor: EventCursor,
    public val envelope: DeviceMessageFrame<DeviceMessage>,
) {
    public val message: DeviceMessage get() = envelope.payload
}

/**
 * Read facet: a replay-capable ordered source of envelopes for a time window. The lowest-common
 * denominator consumed by reconstruction (`timeTravel`, `counterfactual`) in the domain layer.
 */
public fun interface ReplayLog {
    /** Cold flow of messages with timestamps in `[from, until]`. */
    public fun replay(from: Instant, until: Instant): Flow<DeviceMessageFrame<DeviceMessage>>
}

/**
 * Read facet with storage-assigned cursors. Required for deterministic branching: sequence IDs or
 * offsets, not timestamps, define the exact log position.
 */
public interface CursorReplayLog : ReplayLog {
    /** Cold flow of records after [after], or from the beginning when [after] is `null`. */
    public fun replayFrom(after: EventCursor? = null): Flow<ReplayRecord>

    /**
     * Cold flow of cursor records in `[from, until]`. Persistent stores override this and seek their
     * time index instead of scanning from the beginning.
     */
    public fun replayRecords(from: Instant, until: Instant): Flow<ReplayRecord> =
        replayFrom(null)
            .dropWhile { it.envelope.payload.time < from }
            .takeWhile { it.envelope.payload.time <= until }
}

/**
 * Write facet: a sink for envelopes. The producer side of event sourcing, separated from the read
 * facets so recorders depend only on appending.
 */
public fun interface ReplaySink {
    public suspend fun record(message: DeviceMessageFrame<DeviceMessage>)
}

/** Wraps an unbounded [messages] flow as a [ReplayLog]. */
public fun ReplayLog(messages: Flow<DeviceMessageFrame<DeviceMessage>>): ReplayLog =
    ReplayLog { from, until ->
        messages
            .dropWhile { it.payload.time < from }
            .takeWhile { it.payload.time <= until }
    }

/**
 * Replay ordering for envelopes from distributed devices. When both carry HLC stamps, causal time
 * wins; otherwise ordering falls back to payload event time, the only clock comparable across
 * local-only logs.
 */
public fun compareEnvelopesByCausality(
    left: DeviceMessageFrame<DeviceMessage>,
    right: DeviceMessageFrame<DeviceMessage>,
): Int {
    val leftHlc = left.context.hlcTimestamp
    val rightHlc = right.context.hlcTimestamp
    if (leftHlc != null && rightHlc != null) {
        val byHlc = leftHlc.compareTo(rightHlc)
        if (byHlc != 0) return byHlc
    }
    return left.payload.time.compareTo(right.payload.time)
}
