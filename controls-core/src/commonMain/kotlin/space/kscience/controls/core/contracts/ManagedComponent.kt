package space.kscience.controls.core.contracts

import kotlinx.coroutines.flow.StateFlow
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.dataforge.context.ContextAware

/**
 * A contract for any component managed by the runtime, possessing a formal, observable lifecycle.
 * The lifecycle itself is driven by external events (e.g., from a [CompositeDeviceHub]),
 * not by direct calls to `start()` or `stop()` on the component itself.
 *
 * @see DeviceLifecycleState for possible states.
 */
public interface ManagedComponent : ContextAware {
    /**
     * A reactive [kotlinx.coroutines.flow.StateFlow] representing the current state of the component's lifecycle.
     * This provides a safe, observable way to track the component's status.
     */
    public val lifecycleState: StateFlow<DeviceLifecycleState>

    /**
     * A convenience property to check if the component is in the [DeviceLifecycleState.Running] state.
     */
    public val isRunning: Boolean get() = lifecycleState.value == DeviceLifecycleState.Running
}