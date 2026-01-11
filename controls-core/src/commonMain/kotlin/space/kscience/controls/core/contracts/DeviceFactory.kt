package space.kscience.controls.core.contracts

import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name

/**
 * A context provided to the [DeviceFactory] during the device creation phase.
 * It allows the factory to inject dependencies into the device.
 */
public interface DeviceCreationContext : ContextAware {
    public val deviceName: Name
}

/**
 * A factory responsible for instantiating a [Device] based on a [DeviceBlueprint].
 * This replaces the embedded `DeviceDriver`.
 *
 * Factories are registered in the runtime context and discovered by [BlueprintId].
 */
public fun interface DeviceFactory<D : Device> {
    /**
     * Creates a new device instance.
     *
     * @param context The creation context (providing parent context, device name).
     * @param blueprint The blueprint defining the device structure.
     * @param meta The observable configuration meta for the device instance.
     * @return A new, uninitialized instance of the device.
     */
    public fun create(
        context: DeviceCreationContext,
        blueprint: DeviceBlueprint<D>,
        meta: ObservableMeta
    ): D

    public companion object {
        /**
         * The target name used by DataForge's `gather` mechanism to discover DeviceFactory implementations.
         */
        public const val TARGET: String = "device.factory"
    }
}