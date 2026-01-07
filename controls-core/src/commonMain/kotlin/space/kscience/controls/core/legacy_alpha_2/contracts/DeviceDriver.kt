package space.kscience.controls.core.legacy_alpha_2.contracts

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.ObservableMeta

/**
 * A factory responsible for creating an instance of a device and managing its lifecycle via hooks.
 * The driver serves as the bridge between the abstract device old and the physical world (or a simulation).
 * It is a stateless, reusable component.
 *
 * The `DeviceDriver` contract has two primary responsibilities:
 * 1.  **Instantiation:** The [create] method acts as a factory for the device object itself.
 * 2.  **Lifecycle Management:** The `on...` hooks are called by the runtime at specific points in the
 *     device's lifecycle, allowing the driver to manage resources like hardware connections.
 *
 * **IMPORTANT:** The logic for a device's properties and actions is **not** defined here. It must be
 * provided in a `driverLogic { ... }` block within the `DeviceBlueprint`'s definition. This separation
 * ensures that the device's behavior is fully declared in the blueprint.
 *
 * @param D The type of the device contract this driver implements.
 */
public fun interface DeviceDriver<D : Device> {
    /**
     * Creates a new device instance. This method should handle the initial setup of the device object itself.
     * The provided [meta] is observable. A driver implementation can and should subscribe to changes
     * on this meta within its lifecycle hooks (e.g., in `onAttach` or `onStart`) to support
     * dynamic reconfiguration for parameters that allow it. The runtime guarantees that this `ObservableMeta`
     * object will represent the live configuration for the entire lifecycle of the device instance.
     *
     * @param context The DataForge context for the device.
     * @param meta The observable configuration meta for the device.
     * @return A new instance of the device.
     */
    public fun create(context: Context, meta: ObservableMeta): D

    // Lifecycle hooks with default no-op implementations

    /**
     * A hook called by the runtime when the device is attached to the hub.
     * Use this for one-time setup that does not require the device to be active,
     * like validating configuration or preparing resources.
     * @param device The device instance being attached.
     */
    public suspend fun onAttach(device: D) {}

    /**
     * A hook called by the runtime when the device's start sequence is initiated.
     * This method should contain logic to prepare the device for operation,
     * such as opening connections or initializing hardware.
     * @param device The device instance being started.
     */
    public suspend fun onStart(device: D) {}

    /**
     * A hook called by the runtime immediately after the device has successfully started and
     * its lifecycle state has transitioned to [space.kscience.controls.api.lifecycle.DeviceLifecycleState.Running].
     * @param device The device instance that has started.
     */
    public suspend fun afterStart(device: D) {}

    /**
     * A hook called by the runtime when the device's stop sequence is initiated.
     * This method should contain logic for gracefully shutting down the device and releasing resources.
     * @param device The device instance being stopped.
     */
    public suspend fun onStop(device: D) {}

    /**
     * A hook called by the runtime after the device has successfully stopped.
     * @param device The device instance that has stopped.
     */
    public suspend fun afterStop(device: D) {}

    /**
     * A hook called when a reset is triggered on a failed device.
     * Use this to attempt to bring the device back to a stable, stopped state.
     * @param device The device instance being reset.
     */
    public suspend fun onReset(device: D) {}

    /**
     * A hook called when a device enters the [space.kscience.controls.api.lifecycle.DeviceLifecycleState.Failed] state.
     * @param device The device instance that failed.
     * @param error The optional error that caused the failure.
     */
    public suspend fun onFail(device: D, error: Throwable?) {}

    /**
     * A hook called by the runtime when the device is detached from the hub.
     * Use this for final cleanup. After this hook, the device instance should not be used.
     * @param device The device instance being detached.
     */
    public suspend fun onDetach(device: D) {}
}