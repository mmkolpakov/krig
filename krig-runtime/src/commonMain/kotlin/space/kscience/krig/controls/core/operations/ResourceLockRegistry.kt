package space.kscience.krig.core.operations

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import space.kscience.krig.core.InternalKrigApi
import space.kscience.dataforge.names.Name

/**
 * Per-device registry of [Mutex]es keyed by resource name. Lazily allocates on
 * first use; siblings sharing a logical resource share one mutex.
 *
 * All hardware access is exclusive — a physical port cannot service concurrent
 * reads or writes. Standard [Mutex] with FIFO ordering is the correct primitive.
 */
public class ResourceLockRegistry {
    private val lock = SynchronizedObject()
    private val mutexes = mutableMapOf<Name, Mutex>()

    /** [Mutex] for [resourceName]; created on first access, stable thereafter. */
    @InternalKrigApi
    internal fun mutexFor(resourceName: Name): Mutex = synchronized(lock) {
        mutexes.getOrPut(resourceName) { Mutex() }
    }
}
