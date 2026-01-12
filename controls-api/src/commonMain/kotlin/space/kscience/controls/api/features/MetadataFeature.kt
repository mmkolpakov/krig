package space.kscience.controls.api.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A standardized feature DTO for storing descriptive metadata about the device,
 * such as UI tags, categories, human-readable descriptions, or icons.
 *
 * @property tags A set of semantic tags (e.g. ProfileTag, AliasTag).
 * @property description An optional human-readable description of the device instance/blueprint.
 */
@Serializable
@SerialName("feature.metadata")
public data class MetadataFeature(
    val tags: Set<MemberTag> = emptySet(),
    val description: String? = null
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}