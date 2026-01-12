package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A feature that defines the structure of a composite device (its children).
 */
@Serializable
@SerialName("feature.composition")
public data class CompositionFeature(
    val children: Map<Name, ChildComponentConfig>
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}