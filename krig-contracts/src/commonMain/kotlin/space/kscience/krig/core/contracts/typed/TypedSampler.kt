package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.flow.Flow
import space.kscience.attributes.SafeType

/** Streaming view of a typed property. */
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

/** Marker for primitive or value-class sampler specialisations. */
public interface PrimitiveTypedSampler<T> : TypedSampler<T>

/** Primitive double sampler with unboxed latest/snapshot access for hot data-plane reads. */
public interface DoubleSampler : PrimitiveTypedSampler<Double> {
    public val hasLatest: Boolean

    public fun publishDouble(value: Double)
    public fun latestDoubleOrNaN(): Double
    public fun latestDoubleOr(default: Double): Double =
        if (hasLatest) latestDoubleOrNaN() else default

    public fun snapshotDoubleArray(): DoubleArray

    override fun latest(): Double? = if (hasLatest) latestDoubleOrNaN() else null
    override fun snapshot(): List<Double> = snapshotDoubleArray().asList()
}
