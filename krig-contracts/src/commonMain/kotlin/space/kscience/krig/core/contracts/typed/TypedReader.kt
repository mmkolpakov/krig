package space.kscience.krig.core.contracts.typed

/**
 * Typed read handle for a device property. Primary contract on the data plane —
 * `Meta` becomes a serialisation adapter on top of [TypedReader.read].
 *
 * For zero-allocation streaming, use [TypedSampler]; [TypedReader] is the
 * per-call (control-plane) path and may box primitives through the generic interface.
 */
public interface TypedReader<T> {
    public suspend fun read(): T
}

/**
 * Generic reader for any type.
 */
public class GenericTypedReader<T>(
    private val source: suspend () -> T,
) : TypedReader<T> {
    override suspend fun read(): T = source()
}
