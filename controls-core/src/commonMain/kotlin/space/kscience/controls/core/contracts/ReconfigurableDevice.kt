package space.kscience.controls.core.contracts

import space.kscience.controls.api.features.ReconfigurableFeature
import space.kscience.dataforge.meta.Meta

/**
 * A capability interface for devices that can have their configuration updated at runtime
 * without a full restart. A [DeviceBlueprint] must declare this capability for it to be used.
 */
public interface ReconfigurableDevice : Device {
    /**
     * Companion object holding stable identifiers for the capability.
     */
    public companion object {
        /**
         * The unique, fully-qualified name for the [ReconfigurableDevice] capability.
         */
        public const val CAPABILITY: String = ReconfigurableFeature.CAPABILITY_ID
    }

    /**
     * Applies new configuration parameters from the provided [space.kscience.dataforge.meta.Meta] object to the device.
     *
     * This method should be implemented to be atomic where possible. If the configuration is invalid
     * or cannot be applied, it should throw an exception.
     *
     * @param meta A [space.kscience.dataforge.meta.Meta] object containing the new configuration values. The device should
     * only read the parameters it understands and ignore others.
     */
    public suspend fun reconfigure(meta: Meta)
}