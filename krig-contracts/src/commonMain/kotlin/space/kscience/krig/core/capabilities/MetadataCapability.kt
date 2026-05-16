package space.kscience.krig.core.capabilities

import space.kscience.krig.api.meta.MemberTag

/**
 * A runtime capability wrapper for metadata. Exposing metadata as a capability allows uniform
 * access via `device.capability(MetadataCapability)` and leaves room for future dynamic metadata updates.
 *
 * Capability state is `Unit` — metadata is presented through [description] and [tags] directly,
 * not through a separate runtime-state object.
 */
public interface MetadataCapability : DeviceCapability<Unit> {
    public val description: String?
    public val tags: Set<MemberTag>

    override val state: Unit get() = Unit

    public companion object Key : CapabilityKey<MetadataCapability, Unit> {
        override val id: String = "capability.metadata"
    }

    override val key: CapabilityKey<*, Unit> get() = Key
}
