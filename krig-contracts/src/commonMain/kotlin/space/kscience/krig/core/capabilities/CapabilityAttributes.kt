package space.kscience.krig.core.capabilities

import space.kscience.attributes.Attributes
import space.kscience.attributes.AttributesBuilder

/**
 * Read a capability by typed [key] from a builder. Snapshots on each call — avoid in hot loops.
 */
public operator fun <C : Capability<*>> AttributesBuilder<Capability<*>>.get(
    key: CapabilityKey<C>,
): C? = attributes()[key]

/** Installed capabilities. Snapshots on each call — take a single [Attributes] for bulk work. */
public val AttributesBuilder<Capability<*>>.values: Collection<Capability<*>>
    get() = attributes().content.values.filterIsInstance<Capability<*>>()

public val Attributes.capabilityValues: Collection<Capability<*>>
    get() = content.values.filterIsInstance<Capability<*>>()
