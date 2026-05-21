@file:MustUseReturnValues

package space.kscience.krig.storage.journal

import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.ActionFaultMessage
import space.kscience.krig.api.messages.ActionRequestMessage
import space.kscience.krig.api.messages.ActionResponseMessage
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.DeviceDetachedMessage
import space.kscience.krig.api.messages.DeviceErrorMessage
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageType
import space.kscience.krig.api.messages.DeviceOfflineMessage
import space.kscience.krig.api.messages.DeviceOnlineMessage
import space.kscience.krig.api.messages.MessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.PropertyFaultMessage
import space.kscience.krig.api.messages.PropertyReadRequest
import space.kscience.krig.api.messages.PropertyReadResponse
import space.kscience.krig.api.messages.PropertyWriteRequest
import space.kscience.krig.api.messages.PropertyWriteResponse
import space.kscience.krig.api.messages.envelope
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.core.ExperimentalKrigApi

/**
 * Semantic event journal: replay, audit and causality, not high-rate time-series storage.
 */
public interface EventJournal : AutoCloseable {
    /** Appends [event]. */
    public suspend fun write(event: MessageEnvelope<DeviceMessage>)

    /** Convenience payload overload; wraps [event] in an empty envelope context. */
    public suspend fun write(event: DeviceMessage): Unit = write(event.envelope())

    /** Appends every element of [events] in the same unit of work when the backend supports it. */
    public suspend fun writeAll(events: Iterable<MessageEnvelope<DeviceMessage>>): Unit =
        events.forEach { write(it) }

    /** Replays every stored message. */
    public fun readAll(): Flow<MessageEnvelope<DeviceMessage>>

    /**
     * Replays messages whose [DeviceMessage.messageType] equals [messageType], matching
     * optional range / endpoint filters. `null` means "all message types".
     */
    public fun read(
        messageType: String? = null,
        range: ClosedRange<Instant>? = null,
        sourceDevice: Name? = null,
        targetDevice: Name? = null,
    ): Flow<MessageEnvelope<DeviceMessage>>

    /**
     * Hot tail flow of new appends. Emits subsequent writes, never the historical backlog.
     */
    public fun observe(): Flow<MessageEnvelope<DeviceMessage>> = emptyFlow()

    override fun close(): Unit = Unit
}

/** Payload batch helper; wraps each event in an empty envelope context. */
public suspend fun EventJournal.writePayloads(events: Iterable<DeviceMessage>): Unit =
    writeAll(events.map { it.envelope() })

/**
 * Typed [read] overload. Core DTOs use their explicit [DeviceMessage.messageType];
 * custom message subtypes scan all records and then apply [filterIsInstance].
 */
public inline fun <reified T : DeviceMessage> EventJournal.read(
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> {
    val messageType = when (T::class) {
        PropertyChangedMessage::class -> DeviceMessageType.PropertyChanged
        DeviceErrorMessage::class -> DeviceMessageType.DeviceError
        ActionFaultMessage::class -> DeviceMessageType.ActionFault
        DeviceAttachedMessage::class -> DeviceMessageType.DeviceAttached
        DeviceDetachedMessage::class -> DeviceMessageType.DeviceDetached
        PropertyReadRequest::class -> DeviceMessageType.PropertyReadRequest
        PropertyReadResponse::class -> DeviceMessageType.PropertyReadResponse
        PropertyWriteRequest::class -> DeviceMessageType.PropertyWriteRequest
        PropertyWriteResponse::class -> DeviceMessageType.PropertyWriteResponse
        PropertyFaultMessage::class -> DeviceMessageType.PropertyFault
        ActionRequestMessage::class -> DeviceMessageType.ActionExecuteRequest
        ActionResponseMessage::class -> DeviceMessageType.ActionExecuteResponse
        DeviceOnlineMessage::class -> DeviceMessageType.DeviceOnline
        DeviceOfflineMessage::class -> DeviceMessageType.DeviceOffline
        else -> null
    }
    return read(messageType, range, sourceDevice, targetDevice).payloads().filterIsInstance<T>()
}

/** Typed [read] with an explicit storage discriminator for custom message DTOs. */
public inline fun <reified T : DeviceMessage> EventJournal.readTyped(
    messageType: String,
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> = read(messageType, range, sourceDevice, targetDevice).payloads().filterIsInstance<T>()

/**
 * In-memory [EventJournal]. Ordered append, O(n) scan on read, bounded retained history.
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
    private val events: ArrayDeque<MessageEnvelope<DeviceMessage>> = ArrayDeque(minOf(capacity, 1024))
    private val pendingTail: ArrayDeque<MessageEnvelope<DeviceMessage>> = ArrayDeque()
    private var tailDraining: Boolean = false
    private val tail = MutableSharedFlow<MessageEnvelope<DeviceMessage>>(
        extraBufferCapacity = tailBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun write(event: MessageEnvelope<DeviceMessage>) {
        val shouldDrain = synchronized(lock) {
            appendBounded(event)
            enqueueTailLocked(event)
        }
        if (shouldDrain) drainTail()
    }

    override suspend fun writeAll(events: Iterable<MessageEnvelope<DeviceMessage>>) {
        val batch = events.toList()
        if (batch.isEmpty()) return
        val shouldDrain = synchronized(lock) {
            batch.forEach { event ->
                appendBounded(event)
                pendingTail.addLast(event)
            }
            startTailDrainLocked()
        }
        if (shouldDrain) drainTail()
    }

    override fun readAll(): Flow<MessageEnvelope<DeviceMessage>> =
        snapshot().asFlow()

    override fun read(
        messageType: String?,
        range: ClosedRange<Instant>?,
        sourceDevice: Name?,
        targetDevice: Name?,
    ): Flow<MessageEnvelope<DeviceMessage>> = snapshot().asFlow()
        .filter { messageType == null || it.payload.messageType == messageType }
        .filter { range == null || it.payload.time in range }
        .filter { sourceDevice == null || it.payload.sourceDevice == sourceDevice }
        .filter { targetDevice == null || it.payload.targetDevice == targetDevice }

    override fun observe(): Flow<MessageEnvelope<DeviceMessage>> = tail.asSharedFlow()

    private fun snapshot(): List<MessageEnvelope<DeviceMessage>> = synchronized(lock) { events.toList() }

    private fun appendBounded(event: MessageEnvelope<DeviceMessage>) {
        if (events.size >= capacity) {
            events.removeFirst()
        }
        events.addLast(event)
    }

    private fun enqueueTailLocked(event: MessageEnvelope<DeviceMessage>): Boolean {
        pendingTail.addLast(event)
        return startTailDrainLocked()
    }

    private fun startTailDrainLocked(): Boolean {
        if (tailDraining) return false
        tailDraining = true
        return true
    }

    private fun nextTailEventOrStop(): MessageEnvelope<DeviceMessage>? = synchronized(lock) {
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
