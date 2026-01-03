package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that a device provides introspection capabilities,
 * such as exporting its internal Finite State Machine (FSM) diagrams.
 * When a blueprint includes this feature, the runtime is expected to provide
 * implementations for standard introspection actions.
 *
 * @property providesFsmDiagrams If true, the device supports actions to retrieve its FSM diagrams.
 *                               This is the primary flag checked by the runtime.
 */
@Serializable
@SerialName(IntrospectionFeature.ID)
public data class IntrospectionFeature(
    val providesFsmDiagrams: Boolean = false
) : Feature {
    override val key: FeatureKey<*> get() = IntrospectionFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<IntrospectionFeature> {
        public const val ID: String = "feature.introspection"
        public const val CAPABILITY: String = "space.kscience.controls.core.features.Introspection"
        override val id: String = ID
    }
}