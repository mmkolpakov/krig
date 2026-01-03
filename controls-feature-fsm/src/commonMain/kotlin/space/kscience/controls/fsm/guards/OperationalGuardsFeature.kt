package space.kscience.controls.fsm.guards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature that declares a device's use of operational guards.
 * The runtime uses this feature to set up the necessary monitoring and FSM event posting logic.
 */
@Serializable
@SerialName(OperationalGuardsFeature.ID)
public data class OperationalGuardsFeature(val guards: List<GuardSpec>) : Feature {
    override val key: FeatureKey<*> get() = OperationalGuardsFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<OperationalGuardsFeature> {
        public const val ID: String = "feature.operationalGuards"
        public const val CAPABILITY: String = "space.kscience.controls.composite.old.features.Guards"
        override val id: String = ID
    }
}