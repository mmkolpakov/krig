package space.kscience.controls.fsm.events

import kotlinx.serialization.Serializable
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.api.faults.SerializableDeviceFailure

/**
 * Defines the standard vocabulary of events that drive the device's lifecycle state machine.
 * These events correspond to commands issued by a [space.kscience.controls.core.legacy_alpha_2.contracts.DeviceHub].
 */
public sealed interface DeviceLifecycleEvent : Event {
    /** A command to instantiate and attach the device to the hub. */
    @Serializable
    public data object Attach : DeviceLifecycleEvent

    /** A command to start the device's operation. */
    @Serializable
    public data object Start : DeviceLifecycleEvent

    /** A command to stop the device's operation. */
    @Serializable
    public data object Stop : DeviceLifecycleEvent

    /** A command to reset the device from a [space.kscience.controls.api.lifecycle.DeviceLifecycleState.Failed] state back to [space.kscience.controls.api.lifecycle.DeviceLifecycleState.Stopped]. */
    @Serializable
    public data object Reset : DeviceLifecycleEvent

    /** A command to detach and destroy the device instance. */
    @Serializable
    public data object Detach : DeviceLifecycleEvent

    /**
     * An event indicating that an unrecoverable error has occurred.
     * @property failure A serializable representation of the error, suitable for network transmission and persistence.
     */
    @Serializable
    public data class Fail(val failure: SerializableDeviceFailure) : DeviceLifecycleEvent
}