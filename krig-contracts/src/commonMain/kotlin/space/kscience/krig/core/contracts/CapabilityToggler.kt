package space.kscience.krig.core.contracts

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.DeviceCapability

/**
 * Runtime toggle for device capabilities. Allows callers to temporarily suppress
 * a capability's effects without removing it — useful for A/B experiments,
 * emergency overrides, and staged rollouts.
 *
 * Installed capabilities are attached to [AbstractDevice]; toggling a capability
 * does not destroy it, only suppresses its participation in the pipeline.
 */
@OptIn(InternalKrigApi::class)
@InternalKrigApi
public class CapabilityToggler {
    private val lock = SynchronizedObject()
    private val suppressed: MutableSet<String> = mutableSetOf()

    /** Suppresses [key]'s capability. No-op if already suppressed or absent. */
    public fun suppress(key: CapabilityKey<*, *>) {
        synchronized(lock) { suppressed += key.id }
    }

    /** Re-enables a previously suppressed [key]. */
    public fun restore(key: CapabilityKey<*, *>) {
        synchronized(lock) { suppressed -= key.id }
    }

    /** True when [key] is currently suppressed. */
    public fun isSuppressed(key: CapabilityKey<*, *>): Boolean =
        synchronized(lock) { key.id in suppressed }

    /** True when [capability] is currently suppressed. */
    public fun isSuppressed(capability: DeviceCapability<*>): Boolean =
        isSuppressed(capability.key)

    /** All currently suppressed capability ids. */
    public fun suppressedKeys(): Set<String> = synchronized(lock) { suppressed.toSet() }
}

/**
 * Convenience: extends [Device] with a method to check whether a capability
 * is currently toggled on (not suppressed).
 *
 * Usage:
 * ```kotlin
 * if (device.isCapabilityActive(cachingKey)) { ... }
 * ```
 */
@OptIn(InternalKrigApi::class)
public fun Device.isCapabilityActive(key: CapabilityKey<*, *>): Boolean =
    (this as? HasCapabilityToggler)?.toggler?.isSuppressed(key) != true

/**
 * Marker for devices that own a [CapabilityToggler]. [AbstractDevice]
 * provides a default instance.
 */
@OptIn(InternalKrigApi::class)
@InternalKrigApi
public interface HasCapabilityToggler {
    public val toggler: CapabilityToggler
}
