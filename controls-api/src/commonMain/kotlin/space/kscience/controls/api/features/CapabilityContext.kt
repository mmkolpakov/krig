package space.kscience.controls.api.features

import space.kscience.controls.api.io.DeviceIO
import space.kscience.dataforge.context.Context
import kotlin.reflect.KClass

/**
 * A sandbox interface provided to [Capability] instances.
 *
 * It restricts access to the raw [DeviceEntity], preventing "God Object" antipatterns,
 * while providing controlled access to necessary resources.
 */
public interface CapabilityContext {
    /**
     * The device context (for logging and resources).
     */
    public val context: Context

    /**
     * Requests access to the hardware driver interface of a specific type.
     *
     * @param type The class of the required IO interface (e.g., ScalarInputIO::class).
     * @return The driver instance.
     * @throws IllegalArgumentException if the driver does not support the requested interface.
     */
    public fun <T : DeviceIO> requireIO(type: KClass<T>): T
}