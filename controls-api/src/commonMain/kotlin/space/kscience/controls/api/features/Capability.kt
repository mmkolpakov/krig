package space.kscience.controls.api.features

import space.kscience.dataforge.meta.Meta

/**
 * Represents a logic unit (Feature) attached to a device.
 *
 * Capabilities implement high-level logic like FSM, PID control, or connectivity.
 * They MUST NOT hold state that isn't reified in the [PropertyRegistry] to ensure crash recovery.
 */
public interface Capability {
    /**
     * Called when the capability is attached to the device.
     * Use this phase to subscribe to events or bind resources.
     */
    public suspend fun start()

    /**
     * Called when the capability is being stopped or the device is shutting down.
     */
    public suspend fun stop()

    /**
     * Dynamically reconfigures the capability.
     *
     * @param meta The new configuration. The implementation should apply diffs or restart internal jobs.
     */
    public suspend fun reconfigure(meta: Meta)
}