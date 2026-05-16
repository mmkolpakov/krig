package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.flow.Flow
import space.kscience.attributes.SafeType

/**
 * Lock-free streaming view of a typed property — the data-plane analogue of TANGO's
 * polling buffer or DDS's reader cache. Drivers that natively publish into a slot
 * (Modbus polling loop, EPICS monitor callback, simulation tick) expose a sampler so
 * subscribers can read the latest value or observe the stream without going through
 * the per-call read pipeline.
 *
 * **Authorisation**: checked once when the driver returns a sampler from
 * `TypedDevice.sampler(spec)` — subsequent reads do not re-validate.
 *
 * **Concurrency**: [latest] / [snapshot] / [flow] are safe to call from any coroutine.
 * The standard generic implementation is
 * [FlowSampler][space.kscience.krig.core.contracts.sampling.FlowSampler] backed by
 * `MutableSharedFlow`. Hot scalar streams should prefer primitive specialisations
 * such as [DoubleSampler] so latest/snapshot reads do not box every element.
 */
public interface TypedSampler<T> {
    public val type: SafeType<T>
    public val capacity: Int

    /** Returns the most recently published value, or `null` if no value has been published yet. */
    public fun latest(): T?

    /**
     * Returns a defensive snapshot of the buffer in oldest-to-newest order. Allocates
     * once per call; not for the hot path. Use [flow] for streaming consumption.
     */
    public fun snapshot(): List<T>

    /** Cold flow that emits each newly published value; back-pressure handled by the sampler. */
    public fun flow(): Flow<T>
}

/**
 * Open marker for value-class wrapper specialisations. Drivers exposing custom domain
 * value-classes (e.g. `Voltage`, `Temperature`) implement this directly.
 */
public interface PrimitiveTypedSampler<T> : TypedSampler<T>

/** Primitive double sampler with unboxed latest/snapshot access for hot data-plane reads. */
public interface DoubleSampler : PrimitiveTypedSampler<Double> {
    public fun publishDouble(value: Double)
    public fun latestDouble(): Double?
    public fun snapshotDoubleArray(): DoubleArray

    override fun latest(): Double? = latestDouble()
    override fun snapshot(): List<Double> = snapshotDoubleArray().asList()
}
