package space.kscience.controls.core.meta

import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.StreamPort
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.reflect.KType

/**
 * A specification for a device's read-only property. This is a behavioral contract
 * that defines how to interact with the property. It combines the static [PropertyDescriptor]
 * with the logic (`read` function) required to retrieve its value.
 *
 * @param D The type of the device this property belongs to. This is contravariant (`in`) to allow
 *          a spec defined for a supertype to be used with a subtype device.
 * @param T The type of the property's value.
 */
public interface DevicePropertySpec<in D : Device, T> {
    public val name: Name
    public val descriptor: PropertyDescriptor
    public val converter: MetaConverter<T>

    /**
     * The [KType] of the property's value.
     * Essential for compile-time or runtime type validation in bindings.
     * This property is populated by the DSL at creation time.
     */
    public val valueType: KType

    /** The logic to read the property's value from the device instance. */
    public suspend fun read(device: D): T?
}

/**
 * A specification for a mutable device property, extending the read-only version with write logic.
 */
public interface MutableDevicePropertySpec<in D : Device, T> : DevicePropertySpec<D, T> {
    /** The logic to write a new value to the property on the device instance. */
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

    /** The logic to execute the action on the device instance. */
    public suspend fun execute(device: D, input: I): O?
}


/**
 * A specification for a device's data stream. This is a behavioral contract that defines
 * how to access a stream. It combines the static [StreamDescriptor] with a factory function
 * (`get`) to create a live [StreamPort] instance.
 *
 * @param D The type of the device this stream belongs to.
 */
public interface DeviceStreamSpec<in D : Device> {
    public val name: Name
    public val descriptor: StreamDescriptor

    /**
     * A factory function that creates and returns a [StreamPort] for this data stream.
     *
     * **IMPORTANT CONTRACT:** The caller (the `runtime`) is fully responsible for managing the lifecycle of the
     * returned [StreamPort]. The `runtime` **must** call [StreamPort.close] when the owning device is
     * stopped, detached, or otherwise shut down to prevent resource leaks (e.g., open sockets or file handles).
     * The `get` function itself should only create the port, not manage it.
     */
    public val get: suspend D.() -> StreamPort
}