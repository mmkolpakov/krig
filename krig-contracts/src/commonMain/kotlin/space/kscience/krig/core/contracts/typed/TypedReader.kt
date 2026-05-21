package space.kscience.krig.core.contracts.typed

/** Typed read handle for a device property. */
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
