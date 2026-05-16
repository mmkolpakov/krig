@file:MustUseReturnValues

package space.kscience.krig.core.storage

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import space.kscience.krig.api.data.Timestamped
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
import space.kscience.krig.api.messages.PropertyFaultMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.PropertyReadRequest
import space.kscience.krig.api.messages.PropertyReadResponse
import space.kscience.krig.api.messages.PropertyWriteRequest
import space.kscience.krig.api.messages.PropertyWriteResponse
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.krig.core.state.PropertyHistory
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.reflect.KClass
import kotlin.time.Instant

/**
 * Persistent store for [DeviceMessage]s. Shape matches krig data-platform: one
 * read/write surface indexed by time, source and target. Concurrent writers are safe;
 * [readAll]/[read] return cold flows; [observe] is a hot tail flow of new appends.
 */
public interface DeviceMessageStorage : AutoCloseable {
    /** Appends [event]. */
    public suspend fun write(event: DeviceMessage)

    /** Appends every element of [events] in the same unit of work when the backend supports it. */
    public suspend fun writeAll(events: Iterable<DeviceMessage>): Unit = events.forEach { write(it) }

    /** Replays every stored message. */
    public fun readAll(): Flow<DeviceMessage>

    /**
     * Replays messages whose [DeviceMessage.messageType] equals [eventType], matching
     * optional range / endpoint filters. `null` means "all message types".
     */
    public fun read(
        eventType: String? = null,
        range: ClosedRange<Instant>? = null,
        sourceDevice: Name? = null,
        targetDevice: Name? = null,
    ): Flow<DeviceMessage>

    /**
     * Hot tail flow of new appends. Emits every subsequent [write] / [writeAll], never
     * the historical backlog. Subscribe before the expected first write to avoid loss.
     *
     * Default implementation returns an empty cold flow; concrete backends override to
     * expose their own event bus.
     */
    public fun observe(): Flow<DeviceMessage> = emptyFlow()

    override fun close(): Unit = Unit
}

/**
 * Typed [read] overload. Core DTOs use their explicit [DeviceMessage.messageType];
 * custom message subtypes scan all records and then apply [filterIsInstance].
 * Storage backends that support custom message indexes should call [readTyped] with
 * an explicit event type.
 */
public inline fun <reified T : DeviceMessage> DeviceMessageStorage.read(
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> {
    val eventType = messageTypeFor(T::class)
    return read(eventType, range, sourceDevice, targetDevice).filterIsInstance<T>()
}

/** Typed [read] with an explicit storage discriminator for custom message DTOs. */
public inline fun <reified T : DeviceMessage> DeviceMessageStorage.readTyped(
    eventType: String,
    range: ClosedRange<Instant>? = null,
    sourceDevice: Name? = null,
    targetDevice: Name? = null,
): Flow<T> = read(eventType, range, sourceDevice, targetDevice).filterIsInstance<T>()

/** Built-in [DeviceMessage.messageType] for core DTO classes; `null` means no pre-filter. */
public fun messageTypeFor(messageClass: KClass<out DeviceMessage>): String? = when (messageClass) {
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

/**
 * In-memory [DeviceMessageStorage]. Backed by a synchronised list — ordered append,
 * O(n) scan on read. Suitable for tests, short-lived simulations, and as a default
 * fallback before a persistent backend is wired up. It is not durable; retained history
 * is bounded by [capacity] and the hot tail is lossy for slow subscribers. Tail emissions
 * are drained in append order without invoking SharedFlow while the history monitor is held.
 *
 * Filtering uses [DeviceMessage.messageType], not reflection or serializer lookup.
 */
@ExperimentalKrigApi
public class InMemoryDeviceMessageStorage(
    /**
     * Buffer for [observe]'s hot tail. Defaults to 64 with `DROP_OLDEST` — slow tail
     * subscribers don't back-pressure the writer. Tests that need every message
     * delivered should size this to the expected emit volume.
     */
    tailBufferCapacity: Int = 64,
    /**
     * Maximum retained replay history. The live tail is an observability stream, not a
     * durable log; persistent stores should be used when no event may be evicted.
     */
    private val capacity: Int = 100_000,
) : DeviceMessageStorage {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val lock = SynchronizedObject()
    private val events: ArrayDeque<DeviceMessage> = ArrayDeque(minOf(capacity, 1024))
    private val pendingTail: ArrayDeque<DeviceMessage> = ArrayDeque()
    private var tailDraining: Boolean = false
    private val tail = MutableSharedFlow<DeviceMessage>(
        extraBufferCapacity = tailBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public override suspend fun write(event: DeviceMessage) {
        val shouldDrain = synchronized(lock) {
            appendBounded(event)
            enqueueTailLocked(event)
        }
        if (shouldDrain) drainTail()
    }

    public override suspend fun writeAll(events: Iterable<DeviceMessage>) {
        // Snapshot the iterable up front — `addAll` would consume the iterator.
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

    public override fun readAll(): Flow<DeviceMessage> =
        snapshot().asFlow()

    public override fun read(
        eventType: String?,
        range: ClosedRange<Instant>?,
        sourceDevice: Name?,
        targetDevice: Name?,
    ): Flow<DeviceMessage> = snapshot().asFlow()
        .filter { eventType == null || it.messageType == eventType }
        .filter { range == null || it.time in range }
        .filter { sourceDevice == null || it.sourceDevice?.device == sourceDevice }
        .filter { targetDevice == null || it.targetDevice?.device == targetDevice }

    public override fun observe(): Flow<DeviceMessage> = tail.asSharedFlow()

    private fun snapshot(): List<DeviceMessage> = synchronized(lock) { events.toList() }

    private fun appendBounded(event: DeviceMessage) {
        if (events.size >= capacity) {
            events.removeFirst()
        }
        events.addLast(event)
    }

    private fun enqueueTailLocked(event: DeviceMessage): Boolean {
        pendingTail.addLast(event)
        return startTailDrainLocked()
    }

    private fun startTailDrainLocked(): Boolean {
        if (tailDraining) return false
        tailDraining = true
        return true
    }

    private fun nextTailEventOrStop(): DeviceMessage? = synchronized(lock) {
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

/**
 * Storage-backed [PropertyHistory] — replays recorded [PropertyChangedMessage]s for
 * [device] / [property] within the requested time window, decoded through [converter].
 *
 * Intended for post-mortem and counterfactual analysis. Live sliding windows are the
 * concern of `Device.propertyHistory` in `core.state`.
 */
public fun <T> DeviceMessageStorage.propertyHistory(
    device: Name,
    property: Name,
    converter: MetaConverter<T>,
): PropertyHistory<T> = PropertyHistory { from, until ->
    read<PropertyChangedMessage>(
        range = from..until,
        sourceDevice = device,
    )
        .filter { it.property == property }
        .map { message ->
            Timestamped(
                value = converter.read(message.value),
                time = message.time,
            )
        }
}
