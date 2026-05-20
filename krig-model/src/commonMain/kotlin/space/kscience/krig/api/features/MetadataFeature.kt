package space.kscience.krig.api.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.annotations.KrigFeatureSpec
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Standard FeatureSpec DTO for descriptive blueprint metadata.
 *
 * @property description An optional human-readable description of the device instance/blueprint.
 */
@KrigFeatureSpec(id = MetadataFeature.ID)
@Serializable
@SerialName("feature.metadata")
public data class MetadataFeature(
    val description: String? = null
) : FeatureSpec {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        /** Stable FeatureSpec identifier, matches [SerialName]. */
        public const val ID: String = "feature.metadata"

        /** Stable FeatureSpec identifier. */
        public val NAME: Name = ID.asName()
    }
}
