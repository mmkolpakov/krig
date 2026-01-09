package space.kscience.controls.api.io

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * A driver capability for handling continuous data streams.
 *
 * Unlike [ScalarInputIO] or [VectorIO], streams bypass the [space.kscience.controls.core.device.PropertyRegistry]
 * and atomic state storage. They are intended for high-bandwidth data such as:
 * - Audio/Video feeds
 * - Continuous waveform logging (oscilloscopes)
 * - Large file transfers
 *
 * The [token] identifies the specific stream channel (e.g., "Channel A" on an oscilloscope).
 *
 * ### Lifecycle
 * The opened [Source] or [Sink] allows direct access to the transport buffer.
 * The runtime (Capability) is responsible for closing these resources when the stream is no longer needed.
 */
public interface StreamIO : DeviceIO {

    /**
     * Opens an input stream for reading continuous data from the device.
     *
     * @param token The unique token representing the stream channel.
     * @return A [Source] for reading raw bytes. The caller MUST close this source.
     * @throws RuntimeException If the stream cannot be opened or the token is invalid.
     */
    public suspend fun openInput(token: Int): Source

    /**
     * Opens an output stream for writing continuous data to the device.
     *
     * @param token The unique token representing the stream channel.
     * @return A [Sink] for writing raw bytes. The caller MUST close this sink to flush and finish transmission.
     * @throws RuntimeException If the stream cannot be opened or the token is invalid.
     */
    public suspend fun openOutput(token: Int): Sink
}