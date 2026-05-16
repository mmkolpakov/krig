package space.kscience.krig.core.contracts.typed

import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.names.Name

/**
 * Optional typed data-plane SPI for [DeviceBackend] implementations.
 *
 * Backends implement this when they can expose native typed handles without crossing
 * the `Meta` serialization boundary. Returning `null` leaves the device on the
 * documented `Meta` fallback path.
 */
public interface TypedBackend {
    /** Native typed read handle, or `null` to use the `Meta` fallback. */
    public fun <T> reader(spec: DevicePropertySpec<*, T>): TypedReader<T>?

    /** Native typed write handle, or `null` to use the `Meta` fallback. */
    public fun <T> writer(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T>?

    /** Native sampler, or `null` when streaming is not supported. */
    public fun <T> sampler(spec: DevicePropertySpec<*, T>): TypedSampler<T>? = null

    /** Native typed action handle, or `null` to use the `Meta` fallback. */
    public fun <I, O> action(spec: DeviceActionSpec<*, I, O>): TypedAction<I, O>? = null
}

/** Backend type returned by [typedBackend][space.kscience.krig.core.contracts.typed.typedBackend]. */
@OptIn(UnstableKrigForSubclassing::class)
public interface TypedDeviceBackend : DeviceBackend, TypedBackend {
    /** Registered typed property spec by name, used to keep the Meta boundary converter-aware. */
    public fun propertySpec(name: Name): DevicePropertySpec<*, *>? = null

    /** Registered typed action spec by name, used to keep the Meta boundary converter-aware. */
    public fun actionSpec(name: Name): DeviceActionSpec<*, *, *>? = null
}
