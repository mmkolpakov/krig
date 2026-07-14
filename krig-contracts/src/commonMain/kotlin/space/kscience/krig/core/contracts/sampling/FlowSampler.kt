package space.kscience.krig.core.contracts.sampling

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.attributes.SafeType
import space.kscience.attributes.safeTypeOf
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.meta.DevicePropertyContract
import kotlin.jvm.JvmSynthetic

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
    trackQuality: Boolean = false,
) : AbstractRingSampler<T>(capacity, type, trackQuality) {

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

    /** Publishes [value] tagging the slot with [severity] (recorded only when quality is tracked). */
    public fun publish(value: T, severity: QualitySeverity) {
        synchronized(lock) {
            values[reserveSlotLocked(severity.rank)] = value
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

/**
 * Reified factory with smart primitive routing — the single public entry point for obtaining a
 * sampler. Mirrors KMath's `Buffer.auto`: for `Double`/`Int`/`Long` it returns the **unboxed**
 * `Ring*Sampler` (zero-allocation hot path); any other `T` falls back to the boxed [FlowSampler].
 * The static return type is [TypedSampler]; narrow with `as RingDoubleSampler` (or use
 * [doubleSampler]/[intSampler]/[longSampler]) when the unboxed publish surface is required.
 */
@Suppress("UNCHECKED_CAST", "SameParameterValue")
public inline fun <reified T> sampler(capacity: Int = 256, trackQuality: Boolean = false): TypedSampler<T> =
    when (T::class) {
        Double::class -> RingDoubleSampler(capacity, trackQuality) as TypedSampler<T>
        Int::class -> RingIntSampler(capacity, trackQuality) as TypedSampler<T>
        Long::class -> RingLongSampler(capacity, trackQuality) as TypedSampler<T>
        else -> FlowSampler(safeTypeOf(), capacity, trackQuality)
    }

/**
 * Shared bounded ring implementation used by the built-in samplers. It owns index/size bookkeeping,
 * the latest flag, and the boxed reactive [flow] view under one synchronized monitor. Primitive
 * implementations keep their values in unboxed arrays while reusing the wrap-around bookkeeping.
 *
 * External sampler implementations should implement [TypedSampler] instead of inheriting storage
 * and synchronization mechanics from this sealed family.
 */
public sealed class AbstractRingSampler<T> protected constructor(
    final override val capacity: Int,
    final override val type: SafeType<T>,
    trackQuality: Boolean = false,
) : TypedSampler<T> {
    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    @get:JvmSynthetic
    internal val lock: SynchronizedObject = SynchronizedObject()
    private var nextIndex: Int = 0
    private var storedSize: Int = 0
    private var hasLatestValue: Boolean = false
    private var totalWrites: Long = 0
    private var drainedWrites: Long = 0

    /**
     * Optional parallel severity lane (a `Structure-of-Arrays` column), allocated only when
     * [trackQuality] is requested — zero cost otherwise. Stores a single [QualitySeverity.rank] per
     * slot as an unsigned byte (0..255), enough for the standard ladder (GOOD=0/UNCERTAIN=50/BAD=100).
     * The full [space.kscience.krig.api.data.DataQuality] (string code/detail) is **not** on the hot
     * path: it travels the event channel, keeping the per-tick publish allocation-free.
     */
    private val severities: ByteArray? = if (trackQuality) ByteArray(capacity) else null
    private var latestSeverityRank: Int = QualitySeverity.GOOD.rank

    private val updates = MutableSharedFlow<T>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Whether any value has been published; safe to call without holding [lock]. */
    public val hasLatest: Boolean get() = synchronized(lock) { hasLatestValue }

    /** Whether this sampler keeps a per-sample [QualitySeverity] lane (see [trackQuality]). */
    public val tracksQuality: Boolean get() = severities != null

    /** Under a held [lock]: reserves the next write slot and advances ring bookkeeping. */
    @JvmSynthetic
    internal fun reserveSlotLocked(): Int = reserveSlotLocked(QualitySeverity.GOOD.rank)

    /**
     * Under a held [lock]: reserves the next write slot, records [severityRank] in the quality lane
     * (if tracked), and advances ring bookkeeping. The rank is stored as a byte; callers feed
     * [QualitySeverity.rank] (the boxed `DataQuality` never reaches this path).
     */
    @JvmSynthetic
    internal fun reserveSlotLocked(severityRank: Int): Int {
        val slot = nextIndex
        severities?.set(slot, severityRank.toByte())
        latestSeverityRank = severityRank
        nextIndex = (nextIndex + 1) % capacity
        if (storedSize < capacity) storedSize++
        hasLatestValue = true
        totalWrites++
        return slot
    }

    /**
     * Samples overwritten since the last call, i.e. samples lost between two pull-snapshots when the
     * publish rate outran consumption. Reads-and-resets the counter; a non-zero result means the next
     * snapshot has a gap and should be flagged [QualitySeverity.UNCERTAIN] for downstream analytics.
     */
    public fun drainOverrunCount(): Long = synchronized(lock) {
        val lost = (totalWrites - drainedWrites - capacity).coerceAtLeast(0)
        drainedWrites = totalWrites
        lost
    }

    /** Under a held [lock]: number of stored elements. */
    @JvmSynthetic
    internal fun sizeLocked(): Int = storedSize

    /** Under a held [lock]: ring index of the oldest stored element. */
    @JvmSynthetic
    internal fun oldestSlotLocked(): Int = if (storedSize == capacity) nextIndex else 0

    /** Under a held [lock]: whether any value has been published. */
    @JvmSynthetic
    internal fun hasLatestLocked(): Boolean = hasLatestValue

    /**
     * Severity of the most recently published value, or `null` when quality is untracked or nothing
     * has been published yet. Reads the unboxed lane, allocating only the [QualitySeverity] wrapper.
     */
    public fun latestSeverity(): QualitySeverity? = synchronized(lock) {
        if (severities != null && hasLatestValue) QualitySeverity(latestSeverityRank) else null
    }

    /**
     * Defensive snapshot of the severity ranks in oldest-to-newest order, or `null` when quality is
     * untracked. It is column-aligned with a value snapshot ([snapshot] / `snapshot*Array`) only when
     * no publication occurs between the two calls; this API does not provide an atomic value/quality
     * snapshot pair. Returned as unsigned ranks (0..255) so it drops straight into a columnar quality
     * band / Arrow `IntVector` without per-row boxing.
     */
    public fun snapshotSeverityRanks(): IntArray? = synchronized(lock) {
        val lane = severities ?: return@synchronized null
        val count = storedSize
        val start = oldestSlotLocked()
        IntArray(count) { lane[(start + it) % capacity].toInt() and 0xFF }
    }

    /**
     * Whether anyone is currently collecting [flow]. Reading [subscriptionCount] is allocation-free.
     */
    @get:JvmSynthetic
    internal val hasFlowSubscribers: Boolean get() = updates.subscriptionCount.value > 0

    /**
     * Raw emit into the boxed reactive [flow]. Built-in samplers go through [emitToFlowIfObserved] so
     * a primitive is boxed only when a collector exists.
     */
    @JvmSynthetic
    internal fun emitToFlow(value: T) { updates.tryEmit(value) }

    /**
     * Forwards [value] to [flow] only when a collector is attached. `inline` keeps primitive boxing
     * inside the subscriber branch, so the silent telemetry path remains allocation-free.
     */
    @JvmSynthetic
    internal inline fun emitToFlowIfObserved(value: () -> T) {
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
    trackQuality: Boolean = false,
) : AbstractRingSampler<Double>(capacity, safeTypeOf<Double>(), trackQuality) {
    private val values = DoubleArray(capacity)
    private var latestValue: Double = 0.0

    public fun publishDouble(value: Double) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    /** Unboxed hot-path publish tagging the slot with [severityRank] (recorded only when tracked). */
    public fun publishDouble(value: Double, severityRank: Int) {
        synchronized(lock) {
            values[reserveSlotLocked(severityRank)] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Double): Unit = publishDouble(value)

    public fun publish(value: Double, severity: QualitySeverity): Unit = publishDouble(value, severity.rank)

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
    trackQuality: Boolean = false,
) : AbstractRingSampler<Int>(capacity, safeTypeOf<Int>(), trackQuality) {
    private val values = IntArray(capacity)
    private var latestValue: Int = 0

    public fun publishInt(value: Int) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    /** Unboxed hot-path publish tagging the slot with [severityRank] (recorded only when tracked). */
    public fun publishInt(value: Int, severityRank: Int) {
        synchronized(lock) {
            values[reserveSlotLocked(severityRank)] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Int): Unit = publishInt(value)

    public fun publish(value: Int, severity: QualitySeverity): Unit = publishInt(value, severity.rank)

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
    trackQuality: Boolean = false,
) : AbstractRingSampler<Long>(capacity, safeTypeOf<Long>(), trackQuality) {
    private val values = LongArray(capacity)
    private var latestValue: Long = 0L

    public fun publishLong(value: Long) {
        synchronized(lock) {
            values[reserveSlotLocked()] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    /** Unboxed hot-path publish tagging the slot with [severityRank] (recorded only when tracked). */
    public fun publishLong(value: Long, severityRank: Int) {
        synchronized(lock) {
            values[reserveSlotLocked(severityRank)] = value
            latestValue = value
        }
        emitToFlowIfObserved { value }
    }

    public fun publish(value: Long): Unit = publishLong(value)

    public fun publish(value: Long, severity: QualitySeverity): Unit = publishLong(value, severity.rank)

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

public fun doubleSampler(capacity: Int = 256, trackQuality: Boolean = false): RingDoubleSampler =
    RingDoubleSampler(capacity, trackQuality)

public fun intSampler(capacity: Int = 256, trackQuality: Boolean = false): RingIntSampler =
    RingIntSampler(capacity, trackQuality)

public fun longSampler(capacity: Int = 256, trackQuality: Boolean = false): RingLongSampler =
    RingLongSampler(capacity, trackQuality)

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
