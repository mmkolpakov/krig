package space.kscience.controls.api.io

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * A driver capability for handling Bulk Data operations (BLOBs, Arrays, Buffers).
 *
 * This interface is optimized for **Zero-Allocation** and **Zero-Copy** scenarios.
 * Instead of returning `ByteArray` (which forces allocation), it operates directly on `kotlinx-io`
 * [Sink] and [Source] buffers.
 */
public interface VectorIO : DeviceIO {

    /**
     * Reads a binary blob from the device directly into the provided [sink].
     *
     * This method allows the caller to manage buffer allocation and reuse. The driver simply
     * fills the available space or writes a specific amount of data.
     *
     * @param token The property token representing the BLOB channel.
     * @param sink The destination buffer where data will be written.
     * @return The number of bytes actually read and written to the sink.
     */
    public suspend fun readBlob(token: Int, sink: Sink): Long

    /**
     * Writes a binary blob to the device directly from the provided [source].
     *
     * This method allows the driver to consume data directly from a buffer without
     * intermediate array copies.
     *
     * @param token The property token representing the BLOB channel.
     * @param source The source of data to be written.
     * @return The number of bytes actually written to the device.
     */
    public suspend fun writeBlob(token: Int, source: Source): Long
}