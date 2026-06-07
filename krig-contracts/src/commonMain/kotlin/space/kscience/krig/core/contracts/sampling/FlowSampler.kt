package space.kscience.krig.core.contracts.sampling

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.attributes.SafeType
import space.kscience.attributes.safeTypeOf
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.contracts.typed.TypedSampler

/**
 * Generic [TypedSampler] over a **boxed** ring buffer — the fallback for non-primitive value types.
 *
 * Shares the bounded-ring engine and the non-replaying [flow] contract with the primitive samplers:
 * a new collector observes only values published *after* it subscribes (no replay of buffered
 * history), and [latest] / [snapshot] read the stored ring rather than a flow cache. For
 * high-frequency numeric streams prefer the unboxed [RingDoubleSampler] / [RingIntSampler] /
 * [RingLongSampler]; with this generic sampler each published value is boxed.
 */
public class FlowSampler<T>(
    type: SafeType<T>,
    capacity: Int = 256,
) : AbstractRingSampler<T>(capacity, type) {

    @Suppress("UNCHECKED_CAST")
    private val values: Array<T?> = arrayOfNulls<Any?>(capacity) as Array<T?>
    private var latestValue: T? = null

    public fun publish(value: T) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    override fun latest(): T? = synchronized(lock) { if (hasLatestLocked()) latestValue else null }

    @Suppress("UNCHECKED_CAST")
    override fun snapshot(): List<T> = synchronized(lock) {
        val count = sizeLocked()
        val start = oldestSlotLocked()
        List(count) { values[(start + it) % capacity] as T }
    }

    override fun toString(): String = "FlowSampler(type=$type, capacity=$capacity)"
}

/** Reified factory — single public entry point replacing the primitive-specialised factories. */
@Suppress("SameParameterValue")
public inline fun <reified T> sampler(capacity: Int = 256): FlowSampler<T> =
    FlowSampler(safeTypeOf(), capacity)

/**
 * Shared bounded ring engine: holds the index/size bookkeeping, the latest-flag, and the boxed
 * reactive [flow] view under one synchronized monitor. Primitive subclasses own an unboxed backing
 * array and expose the unboxed publish/latest/snapshot surface — the duplicated, error-prone
 * wrap-around arithmetic lives here only once. Also the extension point for custom unboxed samplers.
 */
public abstract class AbstractRingSampler<T>(
    final override val capacity: Int,
    final override val type: SafeType<T>,
) : TypedSampler<T> {
    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    protected val lock: SynchronizedObject = SynchronizedObject()
    private var nextIndex: Int = 0
    private var storedSize: Int = 0
    private var hasLatestValue: Boolean = false

    private val updates = MutableSharedFlow<T>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Whether any value has been published; safe to call without holding [lock]. */
    public val hasLatest: Boolean get() = synchronized(lock) { hasLatestValue }

    /** Under a held [lock]: reserves the next write slot and advances ring bookkeeping. */
    protected fun reserveSlotLocked(): Int {
        val slot = nextIndex
        nextIndex = (nextIndex + 1) % capacity
        if (storedSize < capacity) storedSize++
        hasLatestValue = true
        return slot
    }

    /** Under a held [lock]: number of stored elements. */
    protected fun sizeLocked(): Int = storedSize

    /** Under a held [lock]: ring index of the oldest stored element. */
    protected fun oldestSlotLocked(): Int = if (storedSize == capacity) nextIndex else 0

    /** Under a held [lock]: whether any value has been published. */
    protected fun hasLatestLocked(): Boolean = hasLatestValue

    /**
     * Whether anyone is currently collecting [flow]. Reading [subscriptionCount] is allocation-free.
     */
    protected val hasFlowSubscribers: Boolean get() = updates.subscriptionCount.value > 0

    /**
     * Raw emit into the boxed reactive [flow]. Deliberately **not** part of the protected subclass
     * surface (a direct call boxes the primitive on every tick, even with no collector); subclasses
     * must go through [emitToFlowIfObserved]. `@PublishedApi internal` keeps it reachable from the
     * inline guard while removing it from the footgun-prone API.
     */
    @PublishedApi
    internal fun emitToFlow(value: T) { updates.tryEmit(value) }

    /**
     * The only emit primitive a subclass should call: forwards [value] to [flow] **only** when a
     * collector is attached. `inline` so the value is materialised (and, for a primitive subclass,
     * boxed) strictly inside the subscriber branch — on the silent hot path (no collector, the common
     * telemetry case) the unboxed publish stays zero-allocation. Makes the guard impossible to forget.
     */
    protected inline fun emitToFlowIfObserved(value: () -> T) {
        if (hasFlowSubscribers) emitToFlow(value())
    }

    final override fun flow(): Flow<T> = updates.asSharedFlow()
}

/**
 * Bounded double sampler with an unboxed ring buffer for latest/snapshot reads.
 *
 * [flow] is still a boxed reactive view for UI/control-plane observers; the hot path is
 * [publishDouble], [latestDoubleOr], and [snapshotDoubleArray].
 */
public class RingDoubleSampler(
    capacity: Int = 256,
) : AbstractRingSampler<Double>(capacity, safeTypeOf<Double>()) {
    private val values = DoubleArray(capacity)
    private var latestValue: Double = 0.0

    public fun publishDouble(value: Double) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Double): Unit = publishDouble(value)

    public fun latestDoubleOr(default: Double): Double = synchronized(lock) {
        if (hasLatestLocked()) latestValue else default
    }

    /** Convenience: [latestDoubleOr] with the canonical NaN sentinel. */
    public fun latestDoubleOrNaN(): Double = latestDoubleOr(Double.NaN)

    public fun snapshotDoubleArray(): DoubleArray = synchronized(lock) {
        val count = sizeLocked()
        val start = oldestSlotLocked()
        DoubleArray(count) { values[(start + it) % capacity] }
    }

    override fun latest(): Double? = synchronized(lock) { if (hasLatestLocked()) latestValue else null }
    override fun snapshot(): List<Double> = snapshotDoubleArray().asList()
}

/**
 * Bounded int sampler with an unboxed ring buffer for latest/snapshot reads. Int has no NaN-style
 * sentinel, so [latestIntOr] takes an explicit default; guard with [hasLatest] when needed.
 */
public class RingIntSampler(
    capacity: Int = 256,
) : AbstractRingSampler<Int>(capacity, safeTypeOf<Int>()) {
    private val values = IntArray(capacity)
    private var latestValue: Int = 0

    public fun publishInt(value: Int) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Int): Unit = publishInt(value)

    public fun latestIntOr(default: Int): Int = synchronized(lock) {
        if (hasLatestLocked()) latestValue else default
    }

    public fun snapshotIntArray(): IntArray = synchronized(lock) {
        val count = sizeLocked()
        val start = oldestSlotLocked()
        IntArray(count) { values[(start + it) % capacity] }
    }

    override fun latest(): Int? = synchronized(lock) { if (hasLatestLocked()) latestValue else null }
    override fun snapshot(): List<Int> = snapshotIntArray().asList()
}

/**
 * Bounded long sampler with an unboxed ring buffer for latest/snapshot reads. Long has no NaN-style
 * sentinel, so [latestLongOr] takes an explicit default; guard with [hasLatest] when needed.
 */
public class RingLongSampler(
    capacity: Int = 256,
) : AbstractRingSampler<Long>(capacity, safeTypeOf<Long>()) {
    private val values = LongArray(capacity)
    private var latestValue: Long = 0L

    public fun publishLong(value: Long) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Long): Unit = publishLong(value)

    public fun latestLongOr(default: Long): Long = synchronized(lock) {
        if (hasLatestLocked()) latestValue else default
    }

    public fun snapshotLongArray(): LongArray = synchronized(lock) {
        val count = sizeLocked()
        val start = oldestSlotLocked()
        LongArray(count) { values[(start + it) % capacity] }
    }

    override fun latest(): Long? = synchronized(lock) { if (hasLatestLocked()) latestValue else null }
    override fun snapshot(): List<Long> = snapshotLongArray().asList()
}

public fun doubleSampler(capacity: Int = 256): RingDoubleSampler = RingDoubleSampler(capacity)

public fun intSampler(capacity: Int = 256): RingIntSampler = RingIntSampler(capacity)

public fun longSampler(capacity: Int = 256): RingLongSampler = RingLongSampler(capacity)

/** Returns the unboxed double sampler exposed for [spec], or `null` when the device has no such sampler. */
public fun Device.doubleSampler(spec: DevicePropertyContract<Double>): RingDoubleSampler? =
    sampler(spec) as? RingDoubleSampler

/** Returns the unboxed double sampler exposed for [spec]. */
public fun Device.requireDoubleSampler(spec: DevicePropertyContract<Double>): RingDoubleSampler =
    doubleSampler(spec) ?: error("Device '$name' does not expose a double sampler for '${spec.name}'.")

/** Returns the unboxed int sampler exposed for [spec], or `null` when the device has no such sampler. */
public fun Device.intSampler(spec: DevicePropertyContract<Int>): RingIntSampler? =
    sampler(spec) as? RingIntSampler

/** Returns the unboxed int sampler exposed for [spec]. */
public fun Device.requireIntSampler(spec: DevicePropertyContract<Int>): RingIntSampler =
    intSampler(spec) ?: error("Device '$name' does not expose an int sampler for '${spec.name}'.")

/** Returns the unboxed long sampler exposed for [spec], or `null` when the device has no such sampler. */
public fun Device.longSampler(spec: DevicePropertyContract<Long>): RingLongSampler? =
    sampler(spec) as? RingLongSampler

/** Returns the unboxed long sampler exposed for [spec]. */
public fun Device.requireLongSampler(spec: DevicePropertyContract<Long>): RingLongSampler =
    longSampler(spec) ?: error("Device '$name' does not expose a long sampler for '${spec.name}'.")
