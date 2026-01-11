package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that a device provides introspection capabilities,
 * such as exporting its internal Finite State Machine (FSM) diagrams.
 *
 * @property providesFsmDiagrams If true, the device supports actions to retrieve its FSM diagrams.
 */
@Serializable
@SerialName("feature.introspection")
public data class IntrospectionFeature(
    val providesFsmDiagrams: Boolean = false
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}