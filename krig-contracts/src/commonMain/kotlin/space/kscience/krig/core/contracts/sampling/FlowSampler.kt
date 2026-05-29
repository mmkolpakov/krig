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
import space.kscience.krig.core.contracts.typed.DoubleSampler
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.contracts.typed.PrimitiveTypedSampler
import space.kscience.krig.core.contracts.typed.TypedSampler

/**
 * [TypedSampler] backed by a standard [MutableSharedFlow].
 * For high-frequency data, publish chunked arrays ([DoubleArray]) rather than
 * individual values — one emission replaces N boxing allocations.
 */
public class FlowSampler<T>(
    override val type: SafeType<T>,
    override val capacity: Int,
) : PrimitiveTypedSampler<T> {
    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    private val flow = MutableSharedFlow<T>(
        replay = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public fun publish(value: T) { flow.tryEmit(value) }
    override fun latest(): T? = flow.replayCache.lastOrNull()
    override fun snapshot(): List<T> = flow.replayCache.toList()
    override fun flow(): Flow<T> = flow.asSharedFlow()
    override fun toString(): String = "FlowSampler(type=$type, capacity=$capacity)"
}

/** Reified factory — single public entry point replacing the primitive-specialised factories. */
@Suppress("SameParameterValue")
public inline fun <reified T> sampler(capacity: Int = 256): FlowSampler<T> =
    FlowSampler(safeTypeOf(), capacity)

/**
 * Bounded double sampler with an unboxed ring buffer for latest/snapshot reads.
 *
 * [flow] is still a boxed reactive view for UI/control-plane observers; the hot path is
 * [publishDouble], [latestDoubleOrNaN], [latestDoubleOr], and [snapshotDoubleArray].
 */
public class RingDoubleSampler(
    override val capacity: Int = 256,
) : DoubleSampler {
    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    override val type: SafeType<Double> = safeTypeOf<Double>()

    private val lock = SynchronizedObject()
    private val values = DoubleArray(capacity)
    private var nextIndex: Int = 0
    private var size: Int = 0
    private var hasLatestValue: Boolean = false
    private var latestValue: Double = 0.0

    private val updates = MutableSharedFlow<Double>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun publishDouble(value: Double) {
        synchronized(lock) {
            values[nextIndex] = value
            nextIndex = (nextIndex + 1) % capacity
            if (size < capacity) size++
            latestValue = value
            hasLatestValue = true
        }
        updates.tryEmit(value)
    }

    public fun publish(value: Double): Unit = publishDouble(value)

    override val hasLatest: Boolean get() = synchronized(lock) { hasLatestValue }

    override fun latestDoubleOrNaN(): Double = synchronized(lock) {
        if (hasLatestValue) latestValue else Double.NaN
    }

    override fun latestDoubleOr(default: Double): Double = synchronized(lock) {
        if (hasLatestValue) latestValue else default
    }

    override fun snapshotDoubleArray(): DoubleArray = synchronized(lock) {
        val out = DoubleArray(size)
        val start = if (size == capacity) nextIndex else 0
        repeat(size) { index ->
            out[index] = values[(start + index) % capacity]
        }
        out
    }

    override fun flow(): Flow<Double> = updates.asSharedFlow()
}

public fun doubleSampler(capacity: Int = 256): RingDoubleSampler = RingDoubleSampler(capacity)

/** Returns the primitive double sampler exposed for [spec], or `null` when the device has no such sampler. */
public fun Device.doubleSampler(spec: DevicePropertyContract<Double>): DoubleSampler? =
    sampler(spec) as? DoubleSampler

/** Returns the primitive double sampler exposed for [spec]. */
public fun Device.requireDoubleSampler(spec: DevicePropertyContract<Double>): DoubleSampler =
    doubleSampler(spec) ?: error("Device '$name' does not expose a double sampler for '${spec.name}'.")
