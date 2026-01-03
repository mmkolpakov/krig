package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature that encapsulates the declarative property bindings for a child device.
 *
 * @property bindings A list of bindings defining how the child's properties relate to the parent or constants.
 */
@Serializable
@SerialName(ChildBindingsFeature.ID)
public data class ChildBindingsFeature(
    val bindings: List<PropertyBinding>
) : Feature {
    override val key: FeatureKey<*> get() = ChildBindingsFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<ChildBindingsFeature> {
        public const val ID: String = "feature.childBindings"
        public const val CAPABILITY: String = "space.kscience.controls.connectivity.ChildBindings"

        override val id: String = ID
    }
}