package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmName

/**
 * Pure read-only property contract: serializable [descriptor] plus typed conversion metadata.
 *
 * This is the stable blueprint side of the SDK. Driver logic belongs to a backend or to
 * [DevicePropertySpec] only when using the legacy/executable spec builder.
 */
public interface DevicePropertyContract<T> {
    public val name: Name
    public val descriptor: PropertyDescriptor
    public val converter: MetaConverter<T>
}

/** Pure mutable property contract. Mutability is also reflected in [descriptor] attributes. */
public interface MutableDevicePropertyContract<T> : DevicePropertyContract<T>

/**
 * Pure action contract: serializable [descriptor] plus typed boundary converters.
 *
 * Backends implement actions separately so the same contract can be reused across simulated,
 * declarative, and external driver implementations.
 */
public interface DeviceActionContract<I, O> {
    public val name: Name
    public val descriptor: ActionDescriptor
    public val inputConverter: MetaConverter<I>
    public val outputConverter: MetaConverter<O>
}

/** Read-only executable property spec: [DevicePropertyContract] + read logic bound to a [Device] of type [D]. */
public interface DevicePropertySpec<in D : Device, T> : DevicePropertyContract<T> {

    public suspend fun read(device: D): T?
}

/** Mutable property spec — adds write logic to [DevicePropertySpec]. */
public interface MutableDevicePropertySpec<in D : Device, T> : DevicePropertySpec<D, T>, MutableDevicePropertyContract<T> {
    public suspend fun write(device: D, value: T)
}

/** Executable action spec: [DeviceActionContract] + execute logic binding input [I] to output [O] on [D]. */
public interface DeviceActionSpec<in D : Device, I, O> : DeviceActionContract<I, O> {
    public suspend fun execute(device: D, input: I): O?
}

@JvmName("propertyDescriptorMap")
public fun Collection<DevicePropertyContract<*>>.descriptorMap(): Map<Name, PropertyDescriptor> =
    associate { it.name to it.descriptor }

@JvmName("actionDescriptorMap")
public fun Collection<DeviceActionContract<*, *>>.descriptorMap(): Map<Name, ActionDescriptor> =
    associate { it.name to it.descriptor }
