package space.kscience.controls.core.bundle

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A registry of available [DriverFactory] implementations.
 *
 * This component resolves the string `driverId` from a Blueprint/Manifest into executable code.
 * It is typically populated during the application startup phase via plugins.
 */
public class DriverRegistry {
    private val factories = HashMap<String, DriverFactory>()
    private val lock = SynchronizedObject()

    /**
     * Registers a new driver factory.
     *
     * @param id The unique identifier for the driver type (e.g. "controls.driver.modbus").
     * @param factory The factory instance.
     */
    public fun register(id: String, factory: DriverFactory) {
        synchronized(lock) {
            factories[id] = factory
        }
    }

    /**
     * Retrieves a driver factory by its ID.
     *
     * @return The factory, or `null` if not registered.
     */
    public fun get(id: String): DriverFactory? {
        return synchronized(lock) { factories[id] }
    }
}