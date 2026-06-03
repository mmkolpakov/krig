package space.kscience.krig.core.contracts.typed

/** Typed read handle for a device property. SAM: `TypedReader { source() }`. */
public fun interface TypedReader<T> {
    public suspend fun read(): T
}
