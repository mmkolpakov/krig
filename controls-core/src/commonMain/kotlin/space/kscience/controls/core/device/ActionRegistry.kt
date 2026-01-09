package space.kscience.controls.core.device

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A KMP-safe registry for action handlers.
 *
 * Designed for the "Configuration Phase" (mostly writes) and "Run Phase" (mostly reads).
 * Uses [kotlinx.atomicfu] to maintain an immutable map reference, ensuring zero-lock overhead on reads.
 */
internal class ActionRegistry {
    // Atomic reference to an immutable map.
    private val _handlers = atomic(emptyMap<Name, suspend (Meta?) -> Meta?>())

    /**
     * Current snapshot of handlers. Fast O(1) access.
     */
    val map: Map<Name, suspend (Meta?) -> Meta?> get() = _handlers.value

    /**
     * Registers a new handler.
     * This is a "Slow Path" operation (allocation of new map), usually done only during device startup.
     *
     * @throws IllegalStateException if an action with the same name already exists.
     */
    fun register(name: Name, handler: suspend (Meta?) -> Meta?) {
        _handlers.update { current ->
            if (current.containsKey(name)) {
                error("Action '$name' is already registered in this device.")
            }
            current + (name to handler)
        }
    }

    /**
     * Retrieves a handler by name.
     */
    fun get(name: Name): (suspend (Meta?) -> Meta?)? = map[name]
}