package space.kscience.controls.core.contracts

import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name

/**
 * A context provided to the [DeviceFactory] during the device creation phase.
 *
 * This context serves as a dependency injection root for the new device instance.
 * It provides access to the parent [space.kscience.dataforge.context.Context] (for logging,
 * config, and plugin lookup) and defines the local identity ([deviceName]) of the device
 * being created.
 */
public interface DeviceCreationContext : ContextAware {
    /**
     * The unique local name assigned to the device instance within its future parent container.
     */
    public val deviceName: Name
}

/**
 * A factory responsible for instantiating a [Device] based on a blueprint and configuration.
 *
 * In the architectural context of SCADA 4.0/IoT, this component corresponds to the
 * **Provisioning Service**. Its responsibilities are:
 * 1. Instantiating the concrete [Device] actor (Digital Twin).
 * 2. Creating and injecting the necessary [DeviceConnection] (Hardware Adapter).
 * 3. Applying initial configuration.
 *
 * Factories are registered in the runtime context and are typically discovered by the
 * [space.kscience.controls.api.identifiers.BlueprintId].
 *
 * @param D The specific type of [Device] this factory produces.
 */
public fun interface DeviceFactory<D : Device> {

    /**
     * Creates a new, uninitialized device instance.
     *
     * The factory must not start the device or initiate network connections in this method.
     * It should only construct the object graph. Lifecycle methods (start/stop) will be called
     * later by the runtime.
     *
     * @param context The creation context, providing access to the environment and the assigned name.
     * @param meta The observable configuration meta for the device instance. The device should
     *             subscribe to changes in this meta if it supports dynamic reconfiguration.
     * @return A new instance of the device.
     */
    public fun create(
        context: DeviceCreationContext,
        meta: ObservableMeta
    ): D

    public companion object {
        /**
         * The target name used by DataForge's plugin system to gather [DeviceFactory] implementations.
         */
        public const val TARGET: String = "device.factory"
    }
}