package space.kscience.controls.fsm.capability

import kotlinx.serialization.Serializable
import space.kscience.controls.api.spec.CoreDeviceSpec
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability

/**
 * A capability that manages the device's lifecycle using a formal Finite State Machine (FSM).
 *
 * This capability is responsible for:
 * 1.  Initializing and running the lifecycle FSM (e.g., KStateMachine).
 * 2.  Handling external commands (`start`, `stop`) and injecting them as events into the FSM.
 * 3.  Updating the standard [CoreDeviceSpec.LifecycleState] property on the device when the FSM state changes.
 *
 * This replaces the legacy `LifecycleFeature` and `WithLifecycle` interface.
 */
public interface LifecycleCapability : DeviceCapability {

    /**
     * Sends a command to start the device.
     * This method triggers the `Start` event in the underlying FSM.
     *
     * @param mode An optional start mode (e.g., "warm", "cold").
     */
    public suspend fun start(mode: String? = null)

    /**
     * Sends a command to stop the device.
     * This method triggers the `Stop` event in the underlying FSM.
     */
    public suspend fun stop()

    /**
     * Sends a command to recover the device from a failed state.
     */
    public suspend fun recover()

    override val key: CapabilityKey<LifecycleCapability> get() = Key

    public companion object Key : CapabilityKey<LifecycleCapability> {
        override val id: String = "capability.lifecycle"
    }
}

/**
 * A default configuration for the lifecycle capability, defining timeouts and behavior.
 * This can be used by the runtime to configure the FSM.
 *
 * @property startTimeoutMs Maximum time in milliseconds allowed for the startup sequence.
 * @property stopTimeoutMs Maximum time in milliseconds allowed for the shutdown sequence.
 */
@Serializable
public data class LifecycleCapabilityConfig(
    val startTimeoutMs: Long = 5000L,
    val stopTimeoutMs: Long = 2000L
)