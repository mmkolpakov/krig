package space.kscience.controls.state

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.kscience.controls.core.InternalControlsApi

/**
 * A concrete implementation of the logic required for a stateful device.
 * This class manages the dirty flag and state versioning using atomic operations,
 * making it safe for concurrent access. It is intended to be used as a delegate
 * within a `StatefulDevice` implementation.
 */
public class StatefulDeviceLogic {
//    TODO MutableDeviceState is now removed from alpha-3 core
//    private val stateCache = atomic(emptyMap<String, MutableDeviceState<*>>())
//
//    /**
//     * A thread-safe, idempotent way to get or create a state delegate.
//     * This method is public to be accessible from the `stateful` delegate in another module.
//     */
//    public fun <T> getOrPutState(
//        key: String,
//        provider: () -> MutableDeviceState<T>,
//    ): MutableDeviceState<T> {
//        // Fast path: check if the value already exists without entering the update block.
//        stateCache.value[key]?.let {
//            @Suppress("UNCHECKED_CAST")
//            return it as MutableDeviceState<T>
//        }
//        // Slow path: atomically update the map if the key is missing.
//        val updatedMap = stateCache.updateAndGet { currentMap ->
//            if (currentMap.containsKey(key)) {
//                currentMap
//            } else {
//                currentMap + (key to provider())
//            }
//        }
//        @Suppress("UNCHECKED_CAST")
//        return updatedMap.getValue(key) as MutableDeviceState<T>
//    }


    private val _isDirty = MutableStateFlow(false)
    public val isDirty: StateFlow<Boolean> get() = _isDirty.asStateFlow()

    private val _dirtyVersion = atomic(0L)
    private val _dirtyVersionFlow = MutableStateFlow(0L)
    public val dirtyVersion: StateFlow<Long> get() = _dirtyVersionFlow.asStateFlow()

    /**
     * Manually marks the device state as dirty by incrementing the version.
     */
    public fun markDirty() {
        _isDirty.value = true
        _dirtyVersion.incrementAndGet()
        _dirtyVersionFlow.value = _dirtyVersion.value
    }

    /**
     * Clears the dirty flag only if the current state version matches the expected version.
     * @return `true` if the flag was cleared, `false` otherwise.
     */
    @InternalControlsApi
    public fun clearDirtyFlag(expectedVersion: Long): Boolean {
        // Atomic compare-and-set operation
        val updated = _dirtyVersion.compareAndSet(expectedVersion, expectedVersion)
        if (updated) {
            _isDirty.value = false
        }
        return updated
    }
}