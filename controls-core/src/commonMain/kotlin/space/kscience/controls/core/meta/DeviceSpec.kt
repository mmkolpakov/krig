package space.kscience.controls.core.meta

import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.StreamPort
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.reflect.KType

/**
 * A specification for a device's read-only property.
 *
 * This is a behavioral contract that defines how to interact with the property.
 * It combines the static [PropertyDescriptor] with the runtime logic (`read` function)
 * required to retrieve its value.
 *
 * @param D The type of the device this property belongs to.
 * @param T The type of the property's value.
 */
public interface DevicePropertySpec<in D : Device, T> {
    public val name: Name
    public val descriptor: PropertyDescriptor
    public val converter: MetaConverter<T>

    /**
     * The [KType] of the property's value.
     * Essential for compile-time or runtime type validation in bindings.
     */
    public val valueType: KType

    /**
     * The logic to read the property's value from the device instance.
     */
    public suspend fun read(device: D): T?
}

/**
 * A specification for a mutable device property, extending the read-only version with write logic.
 */
public interface MutableDevicePropertySpec<in D : Device, T> : DevicePropertySpec<D, T> {
    /**
     * The logic to write a new value to the property on the device instance.
     */
    public suspend fun write(device: D, value: T)
}

/**
 * A specification for a device's action.
 *
 * @param D The type of the device this action belongs to.
 * @param I The type of the action's input.
 * @param O The type of the action's output.
 */
public interface DeviceActionSpec<in D : Device, I, O> {
    public val name: Name
    public val descriptor: ActionDescriptor
    public val inputConverter: MetaConverter<I>
    public val outputConverter: MetaConverter<O>

    /**
     * The logic to execute the action on the device instance.
     */
    public suspend fun execute(device: D, input: I): O?
}

/**
 * A specification for a device's high-bandwidth data stream (Data Plane).
 *
 * This contract provides a way to establish a direct binary channel to the device,
 * bypassing the standard message bus.
 *
 * @param D The type of the device this stream belongs to.
 */
public interface DeviceStreamSpec<in D : Device> {
    public val name: Name
    public val descriptor: StreamDescriptor

    /**
     * A factory function that opens a **new** [StreamPort] for this data stream.
     *
     * **Resource Management Contract:**
     * 1. The returned [StreamPort] is a heavyweight resource (e.g., TCP socket, file handle).
     * 2. The caller (the runtime, capability, or client) is fully responsible for managing its lifecycle
     *    and **must** call [StreamPort.close] when the stream is no longer needed.
     * 3. Implementations may return a new connection or a shared one, but the `close` contract
     *    remains the same from the caller's perspective.
     */
    public val open: suspend D.() -> StreamPort
}