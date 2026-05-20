package space.kscience.krig.core.capabilities

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Runtime description capability. Domain-specific metadata belongs in dedicated
 * capabilities or operation attributes.
 */
public interface MetadataCapability : Capability<Unit> {
    public val description: String?

    override val state: Unit get() = Unit

    public companion object Key : CapabilityKey<MetadataCapability> {
        override val id: Name = "capability.metadata".asName()
    }

    override val key: CapabilityKey<*> get() = Key
}
