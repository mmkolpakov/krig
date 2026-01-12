package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature that encapsulates the declarative property bindings for a child device.
 *
 * @property bindings A list of bindings defining how the child's properties relate to the parent or constants.
 */
@Serializable
@SerialName("feature.childBindings")
public data class ChildBindingsFeature(
    val bindings: List<PropertyBinding>
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}