package space.kscience.krig.core.contracts.typed

import space.kscience.krig.core.meta.DevicePropertySpec

/**
 * Typed write handle for a device property — the write counterpart of [TypedReader].
 * For zero-allocation writes, use [TypedSampler] for high-frequency data.
 */
public interface TypedWriter<T> {
    public suspend fun write(value: T)
}

/**
 * Generic writer for any type.
 */
public class GenericTypedWriter<T>(
    private val sink: suspend (T) -> Unit,
) : TypedWriter<T> {
    override suspend fun write(value: T): Unit = sink(value)
}

/** Read-only [DevicePropertySpec]s have no writer; helper to fail fast at call sites. */
public fun <T> readOnlyTypedWriter(spec: DevicePropertySpec<*, T>): TypedWriter<T> =
    GenericTypedWriter { error("Property '${spec.name}' is read-only") }
