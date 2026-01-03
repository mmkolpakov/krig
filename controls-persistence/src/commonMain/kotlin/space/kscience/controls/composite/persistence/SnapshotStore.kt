package space.kscience.controls.composite.persistence

import space.kscience.controls.common.serialization.Base64Bytes
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * Base exception for snapshot store operations.
 */
public open class SnapshotStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when a storage quota is exceeded (e.g., in browser localStorage).
 */
public class SnapshotQuotaException(message: String, cause: Throwable? = null) : SnapshotStoreException(message, cause)

/**
 * Thrown when stored snapshot data is corrupted or cannot be parsed.
 */
public class SnapshotFormatException(message: String, cause: Throwable? = null) : SnapshotStoreException(message, cause)


/**
 * An interface for a storage system that can save and load device state snapshots.
 * A snapshot consists of a primary [Meta] object and an optional map of named binary blobs.
 * Snapshots are identified by the device's [Name].
 *
 * Implementations are responsible for ensuring atomicity; a save or delete operation
 * should apply to both the `Meta` and all associated blobs as a single unit.
 */
public interface SnapshotStore {
    /**
     * Saves a snapshot for a given device name. This includes both the metadata and any
     * associated binary data. If a snapshot for this name already exists, it should be overwritten.
     * The operation should be atomic.
     *
     * @param name The unique name of the device.
     * @param snapshot The [Meta] object representing the device's primary state.
     * @param blobs An optional map of named binary data blobs associated with the snapshot.
     * @throws SnapshotStoreException on general storage failures.
     * @throws SnapshotQuotaException if storage limits are exceeded.
     */
    public suspend fun save(name: Name, snapshot: Meta, blobs: Map<Name, Base64Bytes>? = null)

    /**
     * Loads the latest snapshot for a given device name, including both metadata and binary data.
     *
     * @param name The unique name of the device.
     * @return A [Pair] containing the saved [Meta] snapshot and the map of binary blobs,
     *         or `null` if no snapshot is found for the given name. The map of blobs will be
     *         `null` if the snapshot was saved without any binary data.
     * @throws SnapshotFormatException if the stored data is malformed.
     * @throws SnapshotStoreException on general storage failures.
     */
    public suspend fun load(name: Name): Pair<Meta, Map<Name, Base64Bytes>?>?

    /**
     * Deletes the entire snapshot for a given device name, including its metadata and all
     * associated binary blobs. Does nothing if no snapshot is found.
     * The operation should be atomic.
     *
     * @param name The unique name of the device.
     * @throws SnapshotStoreException on general storage failures.
     */
    public suspend fun delete(name: Name)
}