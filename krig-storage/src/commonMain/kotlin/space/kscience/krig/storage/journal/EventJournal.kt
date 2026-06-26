@file:MustUseReturnValues

package space.kscience.krig.storage.journal

import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.messages.messageTypeOrNull
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.core.ExperimentalKrigApi

/**
 * The single event-sourcing substrate: a durable, ordered, append-only log of [DeviceMessageFrame]s.
 *
 * It is both the write side ([ReplaySink], via [write]/[record]) and the read side
 * ([CursorReplayLog], hence [ReplayLog]) of the same store — there is no separate replay-log type.
 * [write] assigns and returns a monotonic [EventCursor]; persistent backends back that cursor with a
 * sequence ID or offset, which is what makes cursor-based branching deterministic.
 *
 * Semantic event journal: replay, audit and causality, not high-rate time-series storage.
 */
public interface EventJournal : CursorReplayLog, ReplaySink, AutoCloseable {
    /** Appends [event] and returns the cursor the store assigned to it. */
    @IgnorableReturnValue
    public suspend fun write(event: DeviceMessageFrame<DeviceMessage>): EventCursor

    /** [ReplaySink] facet: append without observing the assigned cursor. */
    override suspend fun record(message: DeviceMessageFrame<DeviceMessage>) {
        write(message)
    }

    /** Convenience payload overload; wraps [event] in an empty envelope context. */
    @IgnorableReturnValue
    public suspend fun write(event: DeviceMessage): EventCursor = write(event.frame())

    /** Appends every element of [events] in the same unit of work when the backend supports it. */
    public suspend fun writeAll(events: Iterable<DeviceMessageFrame<DeviceMessage>>): Unit =
        events.forEach { write(it) }

    /** Replays every stored message. */
    public fun readAll(): Flow<DeviceMessageFrame<DeviceMessage>>

    /**
     * Replays messages whose [DeviceMessage.messageType] equals [messageType], matching
     * optional range / endpoint filters. `null` means "all message types".
     */
    public fun read(
        messageType: String? = null,
        range: ClosedRange<Instant>? = null,
        sourceDevice: Name? = null,
        targetDevice: Name? = null,
    ): Flow<DeviceMessageFrame<DeviceMessage>> = readAll()
        .filter { messageType == null || it.payload.messageType == messageType }
        .filter { range == null || it.payload.time in range }
        .filter { sourceDevice == null || it.payload.sourceDevice == sourceDevice }
        .filter { targetDevice == null || it.payload.targetDevice == targetDevice }

    /**
     * [ReplayLog] facet: causally-ordered time-window replay (reconstruction order), as opposed to
     * [read], which preserves write/insertion order for audit.
     */
    override fun replay(from: Instant, until: Instant): Flow<DeviceMessageFrame<DeviceMessage>> =
        replayRecords(from, until).map { it.envelope }

    /**
     * Hot tail flow of new appends. Emits subsequent writes, never the historical backlog.
     *
     * An *optional* capability: the default is an empty flow, meaning «this backend exposes no
     * live tail» — subscribers complete without receiving anything. Backends that can observe
     * appends (in-memory, file) override this; consumers that require a tail should verify the
     * backend documents support rather than assume it.
     */
    public fun observe(): Flow<DeviceMessageFrame<DeviceMessage>> = emptyFlow()

    /**
     * Drops every record up to and including [upTo], reclaiming space once those events are covered
     * by a durable snapshot. Counterpart to a snapshot store's retention prune; the cursor is the
     * monotonic anchor returned by [write], so truncation never shifts the meaning of a later cursor.
     *
     * An *optional* capability: the default retains everything (no-op), meaning «this backend does
     * not reclaim history». Backends that can prune (in-memory, file, SQL) override it.
     */
    public suspend fun truncateBefore(upTo: EventCursor): Unit = Unit

    override fun close(): Unit = Unit
}

/** Payload batch helper; wraps each event in an empty envelope context. */
public suspend fun EventJournal.writePayloads(events: Iterable<DeviceMessage>): Unit =
    writeAll(events.map { it.frame() })

/**
 * Typed [read] overload. The storage discriminator is the message's `@SerialName`, resolved from
 * its serializer — the single source of truth shared with the wire format. Polymorphic, abstract,
 * or non-serializable [T] (e.g. [DeviceMessage] itself) scan all records and apply [filterIsInstance].
 */
public inline fun <reified T : DeviceMessage> EventJournal.read(
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> = read(messageTypeOrNull<T>(), range, sourceDevice, targetDevice)
    .payloads()
    .filterIsInstance<T>()

/** Typed [read] with an explicit storage discriminator for custom message DTOs. */
public inline fun <reified T : DeviceMessage> EventJournal.readTyped(
    messageType: String,
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> = read(messageType, range, sourceDevice, targetDevice).payloads().filterIsInstance<T>()

/**
 * In-memory [EventJournal]. The history is a [PersistentList], so [snapshot] returns the current
 * immutable backing reference in O(1) — even when reads interleave with writes — and a reader never
 * copies the full history. Append and capacity eviction are structural (persistent add / removeAt(0)).
 *
 * Cursors are monotonic [SequenceCursor]s assigned at [write], so dropping old records at [capacity]
 * never shifts the meaning of a stored cursor. This is the single in-memory substrate behind both
 * recording and replay; durable backends override the time-seek read paths.
 */
@ExperimentalKrigApi
public class InMemoryEventJournal(
    /**
     * Buffer for [observe]'s hot tail. Slow tail subscribers do not back-pressure writers.
     */
    tailBufferCapacity: Int = 64,
    /**
     * Maximum retained replay history. Use a persistent backend when no event may be evicted.
     */
    private val capacity: Int = 100_000,
) : EventJournal {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val lock = SynchronizedObject()
    private var events: PersistentList<ReplayRecord> = persistentListOf()

    /**
     * Logical window start: the live history is `events[head, events.size)`. Eviction past [capacity]
     * advances [head] in O(1) instead of rebuilding the persistent vector per write (`removeAt(0)`
     * would be O(N)); the dead prefix is physically dropped in one batched rebuild once it reaches
     * [compactionThreshold], so eviction is amortized O(capacity / compactionThreshold) per write.
     */
    private var head: Int = 0
    private val compactionThreshold: Int = maxOf(1, minOf(1024, capacity))
    private var nextSequence: Long = 0
    private val pendingTail: ArrayDeque<DeviceMessageFrame<DeviceMessage>> = ArrayDeque()
    private var tailDraining: Boolean = false
    private val tail = MutableSharedFlow<DeviceMessageFrame<DeviceMessage>>(
        extraBufferCapacity = tailBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @IgnorableReturnValue
    override suspend fun write(event: DeviceMessageFrame<DeviceMessage>): EventCursor {
        val (cursor, shouldDrain) = synchronized(lock) {
            val assigned = SequenceCursor(nextSequence++)
            appendBounded(ReplayRecord(assigned, event))
            assigned to enqueueTailLocked(event)
        }
        if (shouldDrain) drainTail()
        return cursor
    }

    override suspend fun writeAll(events: Iterable<DeviceMessageFrame<DeviceMessage>>) {
        val batch = events.toList()
        if (batch.isEmpty()) return
        val shouldDrain = synchronized(lock) {
            batch.forEach { event ->
                appendBounded(ReplayRecord(SequenceCursor(nextSequence++), event))
                pendingTail.addLast(event)
            }
            startTailDrainLocked()
        }
        if (shouldDrain) drainTail()
    }

    override fun readAll(): Flow<DeviceMessageFrame<DeviceMessage>> =
        flow { snapshot().forEach { emit(it.envelope) } }

    override fun replayFrom(after: EventCursor?): Flow<ReplayRecord> {
        val afterSequence = sequenceAfter(after)
        return flow {
            snapshot().forEach { if ((it.cursor as SequenceCursor).sequence > afterSequence) emit(it) }
        }
    }

    override fun replayRecords(from: Instant, until: Instant): Flow<ReplayRecord> = flow {
        if (until < from) return@flow
        snapshot()
            .filter { it.envelope.payload.time in from..until }
            .sortedWith(::compareRecordsByCausality)
            .forEach { emit(it) }
    }

    override fun observe(): Flow<DeviceMessageFrame<DeviceMessage>> = tail.asSharedFlow()

    override suspend fun truncateBefore(upTo: EventCursor) {
        require(upTo is SequenceCursor) {
            "InMemoryEventJournal can truncate only by SequenceCursor, got ${upTo::class}."
        }
        synchronized(lock) {
            while (head < events.size && (events[head].cursor as SequenceCursor).sequence <= upTo.sequence) {
                head++
            }
            if (head >= compactionThreshold) {
                events = events.subList(head, events.size).toPersistentList()
                head = 0
            }
        }
    }

    public fun size(): Int = synchronized(lock) { events.size - head }

    /**
     * Returns the live history as an O(1) view over the immutable backing list (sub-list view when a
     * dead prefix is pending compaction). The persistent structure is shared with writers, so a reader
     * never copies the full history and a later compaction cannot mutate an already-returned view.
     */
    private fun snapshot(): List<ReplayRecord> = synchronized(lock) {
        if (head == 0) events else events.subList(head, events.size)
    }

    private fun appendBounded(record: ReplayRecord) {
        events = events.add(record)
        // Evict the oldest in O(1) by advancing the logical window; physically drop the dead prefix
        // only once it grows to a batch, amortizing the persistent rebuild over many writes.
        if (events.size - head > capacity) head++
        if (head >= compactionThreshold) {
            events = events.subList(head, events.size).toPersistentList()
            head = 0
        }
    }

    private fun sequenceAfter(after: EventCursor?): Long {
        if (after == null) return Long.MIN_VALUE
        require(after is SequenceCursor) {
            "InMemoryEventJournal can resume only from SequenceCursor, got ${after::class}."
        }
        return after.sequence
    }

    private fun compareRecordsByCausality(left: ReplayRecord, right: ReplayRecord): Int {
        val byCausal = compareEnvelopesByCausality(left.envelope, right.envelope)
        if (byCausal != 0) return byCausal
        return (left.cursor as SequenceCursor).sequence.compareTo((right.cursor as SequenceCursor).sequence)
    }

    private fun enqueueTailLocked(event: DeviceMessageFrame<DeviceMessage>): Boolean {
        pendingTail.addLast(event)
        return startTailDrainLocked()
    }

    private fun startTailDrainLocked(): Boolean {
        if (tailDraining) return false
        tailDraining = true
        return true
    }

    private fun nextTailEventOrStop(): DeviceMessageFrame<DeviceMessage>? = synchronized(lock) {
        if (pendingTail.isEmpty()) {
            tailDraining = false
            null
        } else {
            pendingTail.removeFirst()
        }
    }

    private fun drainTail() {
        try {
            while (true) {
                val next = nextTailEventOrStop() ?: return
                tail.tryEmit(next)
            }
        } catch (e: Throwable) {
            synchronized(lock) { tailDraining = false }
            throw e
        }
    }
}
