package space.kscience.krig.api.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.annotations.KrigPipelineFeatureSpec
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Standard pipeline-feature DTO for descriptive Manifest metadata.
 *
 * @property description An optional human-readable description of the device instance/Manifest.
 */
@KrigPipelineFeatureSpec(id = MetadataFeature.ID)
@Serializable
@SerialName("feature.metadata")
public data class MetadataFeature(
    val description: String? = null
) : PipelineFeatureSpec {
    public companion object {
        /** Stable pipeline-feature identifier, matches [SerialName]. */
        public const val ID: String = "feature.metadata"

        /** Stable pipeline-feature identifier. */
        public val NAME: Name = ID.asName()
    }
}
