package space.kscience.controls.core.features

import kotlinx.serialization.KSerializer
import space.kscience.controls.api.features.Feature
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * A typed specification that binds a serializable configuration ([Feature])
 * to a runtime behavior contract ([DeviceCapability]).
 *
 * This object serves as a unique identifier for a feature type and provides the necessary
 * metadata (like the serializer) to handle the configuration data.
 *
 * @param F The specific type of the [Feature] DTO.
 * @param C The specific type of the [DeviceCapability] interface.
 * @property id The unique string identifier for this feature type. It acts as the key in the blueprint's feature map.
 * @property serializer The serializer for the configuration DTO.
 */
public abstract class FeatureSpec<F : Feature, C : DeviceCapability>(
    public val id: String,
    public val serializer: KSerializer<F>
) {
    /**
     * The [Name] representation of the ID, used for DataForge plugin lookups.
     */
    public val name: Name = id.asName()
}