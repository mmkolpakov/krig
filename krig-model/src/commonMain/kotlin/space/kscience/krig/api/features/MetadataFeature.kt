package space.kscience.krig.api.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.annotations.KrigFeatureSpec
import space.kscience.krig.api.meta.MemberTag
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A standardized DeviceFeatureSpec DTO for storing descriptive metadata about the device,
 * such as UI tags, categories, human-readable descriptions, or icons.
 *
 * @property tags A set of semantic tags (e.g. ProfileTag).
 * @property description An optional human-readable description of the device instance/blueprint.
 */
@KrigFeatureSpec(id = MetadataFeature.ID)
@Serializable
@SerialName("feature.metadata")
public data class MetadataFeature(
    val tags: Set<MemberTag> = emptySet(),
    val description: String? = null
) : DeviceFeatureSpec {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        /** Stable DeviceFeatureSpec identifier, matches [SerialName]. */
        public const val ID: String = "feature.metadata"
    }
}