package space.kscience.krig.core.contracts.typed

import space.kscience.krig.core.meta.DevicePropertyContract

/** Typed write handle for a device property. SAM: `TypedWriter { sink(it) }`. */
public fun interface TypedWriter<T> {
    public suspend fun write(value: T)
}

/** Read-only [DevicePropertyContract]s have no writer; helper to fail fast at call sites. */
public fun <T> readOnlyTypedWriter(spec: DevicePropertyContract<T>): TypedWriter<T> =
    TypedWriter { error("Property '${spec.name}' is read-only") }
