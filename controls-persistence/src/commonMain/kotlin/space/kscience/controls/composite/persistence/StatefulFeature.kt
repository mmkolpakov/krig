package space.kscience.controls.composite.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.controls.core.state.StatefulDevice
import space.kscience.dataforge.meta.Meta

/**
 * A feature describing the state persistence capabilities of a device.
 */
@Serializable
@SerialName(StatefulFeature.ID)
public data class StatefulFeature(
    val supportsHotRestore: Boolean = false,
) : Feature {
    override val key: FeatureKey<*> get() = StatefulFeature
    override val capability: String get() = StatefulDevice.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<StatefulFeature> {
        public const val ID: String = "feature.stateful"
        override val id: String = ID
    }
}