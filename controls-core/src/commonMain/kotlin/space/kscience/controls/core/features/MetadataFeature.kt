package space.kscience.controls.core.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A standardized feature for storing descriptive metadata about the device,
 * such as UI tags, categories, human-readable descriptions, or icons.
 *
 * @property tags A set of semantic tags (e.g. ProfileTag, AliasTag).
 * @property description An optional human-readable description of the device instance/blueprint.
 */
@Serializable
@SerialName(MetadataFeature.ID)
public data class MetadataFeature(
    val tags: Set<MemberTag> = emptySet(),
    val description: String? = null
) : Feature {
    override val key: FeatureKey<*> get() = MetadataFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<MetadataFeature> {
        public const val ID: String = "feature.metadata"
        public const val CAPABILITY: String = "space.kscience.controls.core.features.Metadata"
        override val id: String = ID
    }
}