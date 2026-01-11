package space.kscience.controls.fsm.capability

import space.kscience.controls.api.spec.CoreDeviceSpec
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability

/**
 * A capability that manages the device's **Operational** Finite State Machine.
 *
 * Unlike the [LifecycleCapability] (which handles Start/Stop/Error), this FSM manages the
 * business logic states (e.g., "Idle", "Moving", "Acquiring", "Calibrating").
 *
 * It is responsible for updating the [CoreDeviceSpec.OperationalState] property.
 */
public interface OperationalFsmCapability : DeviceCapability {

    /**
     * The set of valid state names defined in this operational FSM.
     */
    public val possibleStates: Set<String>

    /**
     * Forces a transition to a specific state, if allowed by the FSM logic.
     * Typically, state transitions should be driven by events or actions, but this
     * method allows for direct manipulation where supported.
     *
     * @param stateName The target state name.
     * @return true if the transition was initiated, false otherwise.
     */
    public suspend fun forceState(stateName: String): Boolean

    override val key: CapabilityKey<OperationalFsmCapability> get() = Key

    public companion object Key : CapabilityKey<OperationalFsmCapability> {
        override val id: String = "capability.operationalFsm"
    }
}