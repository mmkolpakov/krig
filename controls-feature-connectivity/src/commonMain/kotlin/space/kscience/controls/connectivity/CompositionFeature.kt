package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

@Serializable
@SerialName(CompositionFeature.ID)
public data class CompositionFeature(
    val children: Map<Name, ChildComponentConfig>
) : Feature {
    override val key: FeatureKey<*> get() = CompositionFeature
    override val capability: String = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<CompositionFeature> {
        public const val ID: String = "feature.composition"
        public const val CAPABILITY: String = "space.kscience.controls.composition"

        override val id: String = ID
    }
}