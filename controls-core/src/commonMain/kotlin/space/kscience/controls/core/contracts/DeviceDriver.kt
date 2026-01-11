package space.kscience.controls.core.contracts

import space.kscience.controls.api.spec.CoreDeviceSpec
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.ObservableMeta

/**
 * A factory responsible for creating an instance of a device and managing its low-level lifecycle hooks.
 * The driver bridges the abstract [Device] contract with the physical world (or simulation).
 *
 * The driver is responsible for:
 * 1. Instantiating the concrete [Device] object.
 * 2. Initializing standard system properties (defined in [CoreDeviceSpec]).
 * 3. Handling primitive `onStart`/`onStop` signals that come from the `LifecycleCapability`.
 *
 * @param D The type of the device contract this driver implements.
 */
public fun interface DeviceDriver<D : Device> {
    /**
     * Creates a new device instance.
     *
     * The driver **must** ensure that the created device is initialized with the standard
     * system properties defined in [CoreDeviceSpec] (Lifecycle, Health, OpState).
     *
     * @param context The [DeviceCreationContext], providing access to the environment and services.
     * @param meta The observable configuration meta for the device.
     * @return A new instance of the device.
     */
    public fun create(context: DeviceCreationContext, meta: ObservableMeta): D

    /**
     * A hook called when the device is attached to the hub.
     */
    public suspend fun onAttach(device: D) {}

    /**
     * A hook called when the device is detached from the hub.
     */
    public suspend fun onDetach(device: D) {}
}