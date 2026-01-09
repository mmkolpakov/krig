package space.kscience.controls.core.bundle

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.controls.core.capability.CapabilityFactory

/**
 * A registry for [CapabilityFactory] instances.
 * Used by [space.kscience.controls.core.factory.DeviceFactory] to hydrate features defined in the manifest.
 */
public class FeatureRegistry {
    private val factories = HashMap<String, CapabilityFactory>()
    private val lock = SynchronizedObject()

    /**
     * Registers a capability factory.
     */
    public fun register(factory: CapabilityFactory) {
        synchronized(lock) {
            factories[factory.id] = factory
        }
    }

    /**
     * Retrieves a factory by its ID.
     */
    public fun get(id: String): CapabilityFactory? {
        return synchronized(lock) { factories[id] }
    }
}