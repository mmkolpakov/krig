package space.kscience.controls.core.capabilities

import space.kscience.controls.api.features.Feature
import space.kscience.controls.core.contracts.Device
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name

/**
 * A factory responsible for creating a [DeviceCapability] instance from a declarative [Feature] configuration.
 *
 * Implementations of this interface are registered in the runtime context (via Plugins) and are looked up
 * using the [space.kscience.controls.core.features.FeatureSpec.name].
 *
 * @param F The specific type of [Feature] (DTO) configuration.
 * @param C The specific type of [DeviceCapability] produced.
 */
public fun interface CapabilityFactory<F : Feature, C : DeviceCapability> {

    /**
     * Creates a new capability instance.
     *
     * @param context The device's context (access to plugins, loggers).
     * @param device The device instance this capability will attach to.
     * @param feature The configuration DTO from the blueprint.
     * @return A new instance of the capability.
     */
    public fun create(context: Context, device: Device, feature: F): C

    public companion object {
        /**
         * The target name used by DataForge's `gather` mechanism to discover CapabilityFactory implementations.
         */
        public const val TARGET: String = "device.capability.factory"
    }
}