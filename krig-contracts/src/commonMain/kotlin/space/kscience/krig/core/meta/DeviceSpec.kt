package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmName

/** Read-only property spec: [descriptor] + read logic bound to a [Device] of type [D]. */
public interface DevicePropertySpec<in D : Device, T> {
    public val name: Name
    public val descriptor: PropertyDescriptor
    public val converter: MetaConverter<T>

    public suspend fun read(device: D): T?
}

/** Mutable property spec — adds write logic to [DevicePropertySpec]. */
public interface MutableDevicePropertySpec<in D : Device, T> : DevicePropertySpec<D, T> {
    public suspend fun write(device: D, value: T)
}

/** Action spec: [descriptor] + execute logic binding input [I] to output [O] on [D]. */
public interface DeviceActionSpec<in D : Device, I, O> {
    public val name: Name
    public val descriptor: ActionDescriptor
    public val inputConverter: MetaConverter<I>
    public val outputConverter: MetaConverter<O>

    public suspend fun execute(device: D, input: I): O?
}

@JvmName("propertyDescriptorMap")
public fun Collection<DevicePropertySpec<*, *>>.descriptorMap(): Map<Name, PropertyDescriptor> =
    associate { it.name to it.descriptor }

@JvmName("actionDescriptorMap")
public fun Collection<DeviceActionSpec<*, *, *>>.descriptorMap(): Map<Name, ActionDescriptor> =
    associate { it.name to it.descriptor }
