package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

//TODO unify with FsmDescriptor
@Serializable
public data class FsmDescriptor(
    val states: Set<String>,
    val events: Set<String>,
    val initialStateName: String,
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A feature indicating that the device has an operational Finite State Machine (FSM)
 * in addition to its lifecycle FSM. This FSM manages the device's internal logic and operational states.
 *
 * @property states A set of names for the states in the operational FSM.
 * @property events A set of event class names that can drive the operational FSM.
 */
@Serializable
@SerialName(OperationalFsmFeature.ID)
public data class OperationalFsmFeature(
    val states: Set<String>,
    val events: Set<String>,
) : Feature {
    @Transient
    public var fsmBuilder: (suspend BuildingStateMachine.(device: Any, context: Any) -> Unit)? = null
    override val key: FeatureKey<*> get() = OperationalFsmFeature
    override val capability: String = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<OperationalFsmFeature> {
        public const val ID: String = "feature.operationalFsm"
        public const val CAPABILITY: String = "ru.nsk.kstatemachine.statemachine.StateMachine"
        override val id: String = ID
    }
}

/**
 * A feature describing the lifecycle management capabilities of a device.
 */
@Serializable
@SerialName(LifecycleFeature.ID)
public data class LifecycleFeature(
    val supportedStates: Set<String> = setOf(
        "Stopped",
        "Running",
        "Failed"
    ),
    val initialStateName: String = "Stopped",
) : Feature {
    @Transient
    public var fsmBuilder: (suspend BuildingStateMachine.(device: Any, context: Any) -> Unit)? = null
    override val key: FeatureKey<*> get() = LifecycleFeature
    override val capability: String get() = Device.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<LifecycleFeature> {
        public const val ID: String = "feature.lifecycle"
        override val id: String = ID
    }
}