package space.kscience.controls.fsm.guards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.GuardSpec
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature that declares a device's use of operational guards.
 */
@Serializable
@SerialName("feature.operationalGuards")
public data class OperationalGuardsFeature(
    val guards: List<GuardSpec>
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}