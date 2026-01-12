package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device has an operational Finite State Machine (FSM).
 *
 * This feature defines the "Business Logic" states of the device (e.g., Idle, Measuring, Error),
 * distinct from the generic Lifecycle states.
 *
 * @property states A set of valid state names in the FSM.
 * @property events A set of event class names that can drive the FSM.
 * @property initialState The name of the state where the FSM starts upon initialization.
 */
@Serializable
@SerialName("feature.operationalFsm")
public data class OperationalFsmFeature(
    val states: Set<String>,
    val events: Set<String>,
    val initialState: String
) : Feature {
    @Transient
    public var fsmBuilder: (suspend BuildingStateMachine.(device: Any, context: Any) -> Unit)? = null

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A feature describing the lifecycle management capabilities of a device.
 *
 * This DTO describes the *structure* of the lifecycle. The actual implementation mechanism
 * (whether it uses a full KStateMachine or a lightweight AtomicReference switch) is decided by the
 * [space.kscience.controls.fsm.capability.LifecycleCapability] factory in the runtime.
 *
 * @property supportedStates The set of allowed lifecycle states. Defaults to standard [DeviceLifecycleState].
 * @property initialStateName The state the device enters immediately after creation (usually "Stopped" or "Attaching").
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