package space.kscience.krig.core.capabilities

import space.kscience.attributes.Attributes
import space.kscience.attributes.AttributesBuilder

/**
 * Read a capability by typed [key] from a builder. Snapshots on each call — avoid in hot loops.
 */
public operator fun <C : DeviceCapability<*>> AttributesBuilder<DeviceCapability<*>>.get(
    key: CapabilityKey<C, *>,
): C? = attributes()[key]

/** Installed capabilities. Snapshots on each call — take a single [Attributes] for bulk work. */
public val AttributesBuilder<DeviceCapability<*>>.values: Collection<DeviceCapability<*>>
    get() = attributes().content.values.filterIsInstance<DeviceCapability<*>>()

public val Attributes.capabilityValues: Collection<DeviceCapability<*>>
    get() = content.values.filterIsInstance<DeviceCapability<*>>()
