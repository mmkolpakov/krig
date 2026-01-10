package space.kscience.controls.core.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer

/**
 * A port for continuous, low-latency, bidirectional streaming of binary data.
 * Unlike `Port`, which is designed for discrete byte array messages, `StreamPort` is optimized for
 * scenarios involving continuous data flow, such as video feeds, high-frequency sensor data,
 * or waveform transmission. It uses `kotlinx-io`'s [kotlinx.io.Buffer] to minimize data copying and improve performance.
 *
 * The [incoming] flow is expected to complete normally when the port is closed gracefully,
 * or fail with an exception (e.g., PortException) if an unrecoverable communication error occurs.
 */
public interface StreamPort : AutoCloseable {
    /**
     * A hot [kotlinx.coroutines.flow.Flow] of incoming data chunks. Each element is a [kotlinx.io.Buffer] containing a segment
     * of the data stream. The size and boundaries of these chunks are determined by the underlying
     * transport and are not guaranteed to align with logical message boundaries.
     * Consumers of this flow are responsible for reassembling messages if necessary.
     */
    public val incoming: Flow<Buffer>

    /**
     * Sends a chunk of binary data through the port. This is a suspendable function that respects
     * backpressure and may wait if the underlying transport's send buffer is full.
     *
     * @param buffer The [Buffer] containing the data to be sent. The buffer will be read from its
     *               current position to its limit.
     * @throws PortClosedException if the port is not open or has been closed.
     * @throws PortException for other I/O or communication errors.
     */
    public suspend fun send(buffer: Buffer)
}