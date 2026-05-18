package space.kscience.krig.core.contracts.typed

import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.meta.Meta
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
    public fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>?

    /** Native typed write handle, or `null` to use the `Meta` fallback. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>?

    /** Native sampler, or `null` when streaming is not supported. */
    public fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Native typed action handle, or `null` to use the `Meta` fallback. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? = null
}

/** Backend type returned by [typedBackend][space.kscience.krig.core.contracts.typed.typedBackend]. */
@OptIn(UnstableKrigForSubclassing::class)
public interface TypedDeviceBackend : DeviceBackend, TypedBackend {
    /** Registered typed property spec by name, used to keep the Meta boundary converter-aware. */
    public fun propertySpec(name: Name): DevicePropertyContract<*>? = null

    /** Registered typed action spec by name, used to keep the Meta boundary converter-aware. */
    public fun actionSpec(name: Name): DeviceActionContract<*, *>? = null
}

/**
 * Optional batch-read SPI for backends that can acquire several properties in one physical transaction.
 *
 * The contract stays protocol-neutral: [properties] are SDK descriptors, while concrete
 * addresses, sockets, field buses, or vendor APIs live in external integration modules.
 */
@OptIn(UnstableKrigForSubclassing::class)
public interface BatchTypedDeviceBackend : TypedDeviceBackend {
    /**
     * Reads [properties] as one acquisition unit when possible.
     *
     * Implementations should return one [DeviceOutcome] per requested property. Partial failures are
     * values, not exceptions; cancellation must still propagate normally.
     */
    context(device: DeviceEnvironment)
    public suspend fun readBatch(properties: Collection<PropertyDescriptor>): Map<Name, DeviceOutcome<Meta>>
}
