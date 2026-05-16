package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name

@PublishedApi
internal fun <D : Device, T> buildDevicePropertySpec(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind,
    valueTypeId: String,
    read: suspend D.() -> T?,
): DevicePropertySpec<D, T> {
    val descriptor = PropertyDescriptor(
        name = name,
        kind = kind,
        valueTypeId = valueTypeId,
        attributes = setOf(AccessAttribute(readable = true, mutable = false)),
    )
    return object : DevicePropertySpec<D, T> {
        override val name: Name = name
        override val descriptor: PropertyDescriptor = descriptor
        override val converter: MetaConverter<T> = converter
        override suspend fun read(device: D): T? = device.read()
    }
}

@PublishedApi
internal fun <D : Device, T> buildMutableDevicePropertySpec(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind,
    valueTypeId: String,
    read: suspend D.() -> T?,
    write: suspend D.(T) -> Unit,
): MutableDevicePropertySpec<D, T> {
    val descriptor = PropertyDescriptor(
        name = name,
        kind = kind,
        valueTypeId = valueTypeId,
        attributes = setOf(AccessAttribute(readable = true, mutable = true)),
    )
    return object : MutableDevicePropertySpec<D, T> {
        override val name: Name = name
        override val descriptor: PropertyDescriptor = descriptor
        override val converter: MetaConverter<T> = converter
        override suspend fun read(device: D): T? = device.read()
        override suspend fun write(device: D, value: T) = device.write(value)
    }
}
