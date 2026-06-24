package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow

/**
 * Transport-agnostic client contract for the Magix bus. Implementations provide subscribe
 * and broadcast over a concrete transport (SSE, RSocket, MQTT, RabbitMQ, ZMQ, in-process).
 */
@MustUseReturnValues
public interface MagixEndpoint : AutoCloseable {

    /**
     * Subscribes to a [Flow] of [MagixMessage]s from the Magix bus.
     * The returned flow is hot and will continue to receive messages as long as the endpoint is active.
     *
     * @param filter A [MagixMessageFilter] to apply on the server or broker side, reducing
     *               network traffic by only sending relevant messages. Client-side filtering
     *               may still be necessary if the broker does not fully support the filter.
     * @return A [Flow] of incoming [MagixMessage]s that match the filter.
     */
    public fun subscribe(filter: MagixMessageFilter = MagixMessageFilter.ALL): Flow<MagixMessage>

    /**
     * Broadcasts a [MagixMessage] to the Magix bus. This is a "fire-and-forget" operation from the
     * caller's perspective. The underlying implementation may provide different levels of delivery guarantees.
     *
     * @param message The [MagixMessage] to be sent.
     */
    public suspend fun broadcast(message: MagixMessage)

    /**
     * Closes the endpoint and releases any underlying resources, such as network connections.
     * After closing, the endpoint is no longer usable.
     */
    override fun close()

}
