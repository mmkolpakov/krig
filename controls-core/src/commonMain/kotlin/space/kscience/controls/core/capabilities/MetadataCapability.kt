package space.kscience.controls.core.capabilities

import space.kscience.controls.api.features.MetadataFeature
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.core.features.FeatureSpec

/**
 * A runtime capability wrapper for metadata.
 * While metadata is often static, exposing it as a capability allows for:
 * 1. Uniform access via `device.capability(MetadataSpec)`.
 * 2. Potential future extensions for dynamic metadata updates or introspection.
 */
public interface MetadataCapability : DeviceCapability {
    public val description: String?
    public val tags: Set<MemberTag>

    public companion object Key : CapabilityKey<MetadataCapability> {
        override val id: String = "capability.metadata"
    }

    override val key: CapabilityKey<*> get() = Key
}

/**
 * Typed specification binding [MetadataFeature] (API) to [MetadataCapability] (Core).
 */
public object MetadataSpec : FeatureSpec<MetadataFeature, MetadataCapability>(
    id = "feature.metadata",
    serializer = MetadataFeature.serializer()
)