package space.kscience.controls.core.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.core.contracts.ReconfigurableDevice
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor

/**
 * A feature describing the device's ability to be reconfigured at runtime.
 */
@Serializable
@SerialName(ReconfigurableFeature.ID)
public data class ReconfigurableFeature(
    val reconfigDescriptor: MetaDescriptor = MetaDescriptor.EMPTY,
) : Feature {
    override val key: FeatureKey<*> get() = ReconfigurableFeature
    override val capability: String get() = ReconfigurableDevice.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<ReconfigurableFeature> {
        public const val ID: String = "feature.reconfigurable"
        override val id: String = ID
    }
}
