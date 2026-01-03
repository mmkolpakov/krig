package space.kscience.controls.api.lifecycle

/**
 * Defines the standard vocabulary of states for a device's lifecycle state machine.
 * This enum represents the *names* of the lifecycle states. The actual state machine
 * implementation (e.g., using KStateMachine) will create state objects corresponding
 * to these enum entries. This approach decouples the old from the FSM library.
 */
public enum class DeviceLifecycleState {
    /** The device blueprint is known, but no instance has been created or attached yet. */
    Detached,

    /** The device instance is being created, and its children are being recursively attached. */
    Attaching,

    /** The device is fully attached and initialized but not running. It is ready to be started. */
    Stopped,

    /** The device is executing its start sequence, including all pre-start logic. */
    Starting,

    /** The device is fully operational and running. */
    Running,

    /** The device is executing its stop sequence. */
    Stopping,

    /** The device has encountered an unrecoverable error and is not operational. It may be restarted or reset. */
    Failed,

    /** The device instance is being removed from the hub, and its resources are being released. */
    Detaching
}