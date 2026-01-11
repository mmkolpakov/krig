package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device has an operational Finite State Machine (FSM).
 *
 * @property states A set of names for the states in the operational FSM.
 * @property events A set of event class names that can drive the operational FSM.
 */
@Serializable
@SerialName("feature.operationalFsm")
public data class OperationalFsmFeature(
    val states: Set<String>,
    val events: Set<String>,
) : Feature {
    @Transient
    public var fsmBuilder: (suspend BuildingStateMachine.(device: Any, context: Any) -> Unit)? = null

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A feature describing the lifecycle management capabilities of a device.
 */
@Serializable
@SerialName("feature.lifecycle")
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

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}