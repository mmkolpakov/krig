package space.kscience.controls.api.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor

/**
 * A feature describing the device's ability to be reconfigured at runtime.
 *
 * @property reconfigDescriptor A descriptor defining the structure of the configuration meta
 *                              that this device accepts via the reconfiguration interface.
 */
@Serializable
@SerialName("feature.reconfigurable")
public data class ReconfigurableFeature(
    val reconfigDescriptor: MetaDescriptor = MetaDescriptor.EMPTY,
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}