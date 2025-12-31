package space.kscience.controls.fsm.guards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature that declares a device's use of operational guards.
 * The runtime uses this feature to set up the necessary monitoring and FSM event posting logic.
 */
@Serializable
@SerialName("feature.operationalGuards")
public data class OperationalGuardsFeature(val guards: List<GuardSpec>) : Feature {
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        /**
         * The unique, fully-qualified name for the OperationalGuards capability.
         */
        public const val CAPABILITY: String = "space.kscience.controls.composite.old.features.Guards"
    }
}