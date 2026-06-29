package space.kscience.krig.core.contracts.typed

import space.kscience.krig.api.data.ObservedValue

/** Typed read handle for a device property. SAM: `TypedReader { source() }`. */
public fun interface TypedReader<T> {
    public suspend fun read(): T
}

/** Typed read handle that preserves source timestamp and data quality. */
public fun interface TypedObservedReader<T> {
    public suspend fun readObserved(): ObservedValue<T?>
}
