@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.core.timetravel

import kotlin.jvm.JvmInline
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.time.Instant
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.core.ExperimentalKrigApi

/**
 * Write-only sink for [DeviceMessage] envelopes. The producer side of event sourcing: implemented
 * by persistence integrations (SQLite audit log, file journal, …).
 */
public fun interface ReplaySink {
    public suspend fun record(message: DeviceMessageEnvelope<DeviceMessage>)
}

/**
 * Combined sink + cursor log. [record] appends, [replay] queries by timestamp,
 * and [replayFrom] provides deterministic sequence-based iteration.
 */
public interface RecordingReplayLog : ReplaySink, CursorReplayLog

/**
 * In-memory cursor: a monotonic sequence assigned when the event is recorded.
 */
@JvmInline
public value class SequenceCursor(public val sequence: Long) : EventCursor {
    override fun compareTo(other: EventCursor): Int =
        sequence.compareTo((other as SequenceCursor).sequence)
}

/**
 * Bounded append-only in-memory [RecordingReplayLog].
 *
 * Events are appended in arrival order. Cursors are monotonic sequence IDs, so
 * dropping old records at [capacity] never shifts the meaning of a stored cursor.
 *
 * A short in-memory critical section is intentionally used instead of a CAS loop
 * over persistent collections: this store is for tests and HIL runs, where
 * predictable append cost beats retrying tree rebuilds under contention.
 *
 * Time-range reads take a snapshot, filter by payload event time, and sort by
 * envelope causal order when both records have HLC stamps; otherwise they fall back to
 * `(message.time, sequence)`. Use a durable implementation overriding
 * [CursorReplayLog.replayRecords] with a database/B-tree time index for
 * production-scale history queries.
 *
 * @param capacity maximum number of events retained; oldest events are dropped
 *                 when exceeded. Default 10 000 protects against OOM in HIL runs.
 */
@ExperimentalKrigApi
public class InMemoryReplayLog(
    private val capacity: Int = 10_000,
) : RecordingReplayLog {
    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    private val lock = SynchronizedObject()
    private val log = ArrayDeque<ReplayRecord>(capacity)
    private var nextSequence: Long = 0

    override suspend fun record(message: DeviceMessageEnvelope<DeviceMessage>) {
        synchronized(lock) {
            val record = ReplayRecord(SequenceCursor(nextSequence++), message)
            log.addLast(record)
            if (log.size > capacity) {
                log.removeFirst()
            }
        }
    }

    override fun replay(from: Instant, until: Instant): Flow<DeviceMessageEnvelope<DeviceMessage>> =
        timeSnapshot(from, until)
            .asSequence()
            .map { it.envelope }
            .asFlow()

    override fun replayFrom(after: EventCursor?): Flow<ReplayRecord> {
        val afterSequence = sequenceAfter(after)
        return snapshot()
            .asSequence()
            .filter { (it.cursor as SequenceCursor).sequence > afterSequence }
            .asFlow()
    }

    override fun replayRecords(from: Instant, until: Instant): Flow<ReplayRecord> =
        timeSnapshot(from, until).asFlow()

    public fun size(): Int = synchronized(lock) { log.size }

    private fun snapshot(): List<ReplayRecord> = synchronized(lock) { log.toList() }

    private fun timeSnapshot(from: Instant, until: Instant): List<ReplayRecord> {
        if (until < from) return emptyList()
        return snapshot()
            .asSequence()
            .filter { it.envelope.payload.time in from..until }
            .sortedWith(::compareRecords)
            .toList()
    }

    private fun compareRecords(left: ReplayRecord, right: ReplayRecord): Int {
        val byEnvelope = compareEnvelopesByCausality(left.envelope, right.envelope)
        if (byEnvelope != 0) return byEnvelope
        return (left.cursor as SequenceCursor).sequence.compareTo((right.cursor as SequenceCursor).sequence)
    }

    private fun sequenceAfter(after: EventCursor?): Long {
        if (after == null) return Long.MIN_VALUE
        require(after is SequenceCursor) {
            "InMemoryReplayLog can resume only from SequenceCursor, got ${after::class}."
        }
        return after.sequence
    }
}
