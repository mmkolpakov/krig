package space.kscience.controls.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.dataforge.names.Name

/**
 * Represents an active data stream channel.
 *
 * This interface is optimized for continuous data flow using `kotlinx-io` [Buffer].
 * It is intended for video feeds, audio, raw sensor waveforms, or high-frequency logs.
 */
public interface StreamChannel : AutoCloseable {
    /**
     * A hot [Flow] of incoming data chunks.
     * Consumers must be prepared to handle backpressure or dropped frames depending on the implementation.
     */
    public val incoming: Flow<Buffer>

    /**
     * Sends a data chunk into the stream.
     *
     * @param buffer The data to send.
     */
    public suspend fun send(buffer: Buffer)
}

/**
 * A capability that provides access to high-bandwidth data streams.
 *
 * Devices that generate continuous data (like cameras or digitizers) should expose this capability.
 * This separates the "Control Plane" (Properties/Actions) from the "Data Plane" (Streams).
 */
public interface StreamCapability : DeviceCapability {

    /**
     * Opens a stream channel by its name.
     *
     * @param name The name of the stream to open (e.g., "video_feed", "adc_channel_1").
     * @return An active [StreamChannel]. The caller is responsible for closing it.
     * @throws IllegalArgumentException if the stream name is unknown.
     * @throws IllegalStateException if the stream cannot be opened (e.g., device is busy).
     */
    public suspend fun openStream(name: Name): StreamChannel

    /**
     * Lists the names of available streams on this device.
     */
    public val listStreams: Set<Name>

    override val key: CapabilityKey<StreamCapability> get() = Key

    public companion object Key : CapabilityKey<StreamCapability> {
        override val id: String = "capability.telemetry.stream"
    }
}