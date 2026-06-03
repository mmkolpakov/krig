package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.flow.Flow
import space.kscience.attributes.SafeType

/**
 * Streaming view of a typed property — the generic data-plane contract a device exposes.
 *
 * Unboxed access is a property of the concrete implementation, not of this interface: a primitive
 * ring sampler (`RingDoubleSampler` / `RingIntSampler` / `RingLongSampler`) is reached by casting,
 * mirroring how KMath exposes `Buffer<T>` and lets callers narrow to `Float64Buffer` for unboxed work.
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
