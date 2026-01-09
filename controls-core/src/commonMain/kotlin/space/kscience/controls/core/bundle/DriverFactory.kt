package space.kscience.controls.core.bundle

import space.kscience.controls.api.io.DeviceIO
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A functional interface for a factory that creates [DeviceIO] instances (Drivers).
 *
 * Drivers are the bridge between the logical device actor and the physical hardware.
 * They are instantiated based on the [space.kscience.controls.api.structure.DeviceManifest.driverId].
 */
public fun interface DriverFactory {
    /**
     * Creates a new driver instance.
     *
     * @param context The context in which the driver operates.
     * @param config The configuration metadata specific to this driver (e.g., connection parameters).
     * @return A configured [DeviceIO] instance.
     */
    public fun create(context: Context, config: Meta): DeviceIO
}