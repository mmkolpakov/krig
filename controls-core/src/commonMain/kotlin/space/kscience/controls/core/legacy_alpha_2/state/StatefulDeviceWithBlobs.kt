package space.kscience.controls.core.legacy_alpha_2.state

import space.kscience.dataforge.names.Name

/**
 * An extension of [StatefulDevice] for devices that need to persist large binary data
 * (or "blobs"), such as internal database files or configuration artifacts, which are not
 * well-suited for storage within a `Meta` object.
 *
 * The runtime persistence layer should check if a device implements this interface.
 * If it does, the runtime will call `snapshotBlobs` in addition to `snapshot` and
 * pass the results to a `SnapshotStore` that can handle binary data.
 */
public interface StatefulDeviceWithBlobs : StatefulDevice {
    /**
     * Creates a snapshot of the device's binary state artifacts.
     * This method is called by the persistence layer alongside [snapshot] when saving state.
     *
     * The key of the map is a logical name for the binary artifact (e.g., "database", "firmware_cache"),
     * and the value is its raw byte content.
     *
     * @return A map of named binary data blobs, or `null` if there are no blobs to save.
     */
    public suspend fun snapshotBlobs(): Map<Name, ByteArray>? = null

    /**
     * Restores the device's state from binary artifacts.
     * This method is called by the persistence layer alongside [restore] when loading state.
     *
     * @param blobs A map of named binary data blobs read from the snapshot store.
     */
    public suspend fun restoreBlobs(blobs: Map<Name, ByteArray>) {}
}