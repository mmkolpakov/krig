package space.kscience.krig.core.contracts

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.dataforge.names.Name

/** Runtime toggle registry for capabilities owned by a [CapabilityHost]. */
public class CapabilityToggles {
    private val lock = SynchronizedObject()
    private val suppressed: MutableSet<Name> = mutableSetOf()

    /** Suppresses [key]'s capability. No-op if already suppressed or absent. */
    public fun suppress(key: CapabilityKey<*>) {
        suppress(key.id)
    }

    /** Suppresses the capability identified by [id]. */
    public fun suppress(id: Name) {
        synchronized(lock) { suppressed += id }
    }

    /** Re-enables a previously suppressed [key]. */
    public fun restore(key: CapabilityKey<*>) {
        restore(key.id)
    }

    /** Re-enables a previously suppressed capability [id]. */
    public fun restore(id: Name) {
        synchronized(lock) { suppressed -= id }
    }

    /** True when [key] is currently suppressed. */
    public fun isSuppressed(key: CapabilityKey<*>): Boolean =
        isSuppressed(key.id)

    /** True when capability [id] is currently suppressed. */
    public fun isSuppressed(id: Name): Boolean =
        synchronized(lock) { id in suppressed }

    /** True when [capability] is currently suppressed. */
    public fun isSuppressed(capability: Capability<*>): Boolean =
        isSuppressed(capability.key)

    /** All currently suppressed capability ids. */
    public fun suppressedKeys(): Set<Name> = synchronized(lock) { suppressed.toSet() }
}

/** True when [key]'s capability is not suppressed on this host. */
public fun CapabilityHost.isCapabilityActive(key: CapabilityKey<*>): Boolean =
    !capabilityToggles.isSuppressed(key)
