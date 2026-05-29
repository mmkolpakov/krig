package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmName

/**
 * Pure read-only property contract: serializable [descriptor] plus typed conversion metadata.
 *
 * Manifest-side contract. Driver logic belongs to a backend.
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

@JvmName("propertyDescriptorMap")
public fun Collection<DevicePropertyContract<*>>.descriptorMap(): Map<Name, PropertyDescriptor> =
    associate { it.name to it.descriptor }

@JvmName("actionDescriptorMap")
public fun Collection<DeviceActionContract<*, *>>.descriptorMap(): Map<Name, ActionDescriptor> =
    associate { it.name to it.descriptor }
