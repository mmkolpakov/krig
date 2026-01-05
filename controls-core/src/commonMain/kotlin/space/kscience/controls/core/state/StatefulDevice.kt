package space.kscience.controls.core.state

import space.kscience.controls.core.contracts.Device

/**
 * A capability interface for devices that can have their state saved and restored.
 * This is used by persistence mechanisms to manage device state across restarts.
 * A [space.kscience.controls.core.contracts.DeviceBlueprint] must declare this capability for it to be used.
 */
public interface StatefulDevice : Device {
    /**
     * Companion object holding stable identifiers for the capability.
     */
    public companion object {
        /**
         * The unique, fully-qualified name for the [StatefulDevice] capability.
         */
        public const val CAPABILITY: String = "space.kscience.controls.core.state.StatefulDevice"
    }

//    /**
//     * A delegate providing thread-safe logic for managing stateful properties.
//     */
//    public val statefulLogic: StatefulDeviceLogic
//
//    /**
//     * A flow that indicates whether the device's state has changed since the last save.
//     * `true` means the state is "dirty" and should be persisted.
//     * The runtime uses this flag to optimize periodic saving.
//     */
//    public val isDirty: StateFlow<Boolean> get() = statefulLogic.isDirty
//
//    /**
//     * A monotonically increasing version of the device's state. Incremented by [markDirty].
//     */
//    public val dirtyVersion: StateFlow<Long> get() = statefulLogic.dirtyVersion
//
//    /**
//     * Manually marks the device state as dirty by incrementing the [dirtyVersion].
//     * This is typically called internally when a stateful property changes.
//     */
//    public suspend fun markDirty() {
//        statefulLogic.markDirty()
//    }

    /**
     * Creates a snapshot of the device's current logical state, including its version.
     * This method should capture all serializable properties and critical state information.
     *
     * @return A [StateSnapshot] object representing the device's state.
     */
    public suspend fun snapshot(): StateSnapshot

//    /**
//     * Clears the dirty flag only if the current state version matches the expected version.
//     * This provides a Check-and-Set (CAS) semantic to prevent race conditions where the state
//     * changes between snapshot creation and persistence completion.
//     *
//     * This method is intended to be called by the persistence layer after a successful save.
//     *
//     * @return `true` if the flag was cleared, `false` otherwise (meaning the state has changed again).
//     */
//    @OptIn(InternalControlsApi::class)
//    public suspend fun clearDirtyFlag(expectedVersion: Long): Boolean = statefulLogic.clearDirtyFlag(expectedVersion)

    /**
     * Restores the device's state from a given snapshot.
     * This is typically called on a stopped device before it is started.
     * The implementation should check the `schemaVersion` for compatibility.
     *
     * @param snapshot The [StateSnapshot] object containing the state to restore.
     */
    public suspend fun restore(snapshot: StateSnapshot)
}
