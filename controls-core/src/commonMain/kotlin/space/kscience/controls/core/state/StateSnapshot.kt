package space.kscience.controls.core.state

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta

/**
 * A serializable container for a device's state snapshot, coupling the state data
 * with a version number for optimistic locking and a schema version for migration.
 *
 * @property version A monotonically increasing version number of the state, used for optimistic locking.
 * @property schemaVersion An integer representing the version of the state's schema. This is intended to be
 *                         used by a `DeviceMigrator` to correctly handle state transformations when
 *                         upgrading a device from an older blueprint version.
 * @property state A [Meta] object representing the device's state.
 */
@Serializable
public data class StateSnapshot(
    val version: Long,
    val schemaVersion: Int = 1,
    val state: Meta,
)