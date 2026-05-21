package space.kscience.krig.core.contracts.typed

import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/** Typed access surface for property reads, writes, actions and optional samplers. */
public interface TypedDevice {
    /** Returns a typed read handle for the given property spec. */
    public fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>

    /** Returns a typed write handle for the given mutable property spec. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>

    /** Returns a typed sampler for this property, or `null` if the driver has none. */
    public fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Returns a typed action handle for the given action spec. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        GenericTypedAction { error("Action '${spec.name}' is not supported by this device") }
}
