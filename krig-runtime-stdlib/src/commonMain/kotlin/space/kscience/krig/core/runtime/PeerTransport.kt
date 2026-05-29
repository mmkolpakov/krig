@file:MustUseReturnValues

package space.kscience.krig.core.runtime

import kotlinx.coroutines.flow.Flow
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmInline

@JvmInline
public value class PeerRoute(public val name: Name)

/**
 * Pluggable P2P transport layer under [BinaryPeerConnection] and any device-link
 * abstraction implemented in transport-adapter modules. Distinct from pub/sub bus
 * transports — this is direct device-to-device.
 *
 * The three primitives map directly to RSocket semantics (request-response, request-stream,
 * fire-and-forget) and also ride cleanly on gRPC (unary, server-streaming, one-way),
 * kotlinx-rpc and NATS.
 */
public interface PeerTransport : AutoCloseable {
    /** Stable peer identifier — typically `scheme://host:port/deviceId`. */
    public val peerId: Name

    /** Request/response — basis for content-addressable binary transfer. */
    public suspend fun requestResponse(route: PeerRoute, payload: Binary): Binary

    /** Hot server-push flow — basis for live message streaming. */
    public fun requestStream(route: PeerRoute): Flow<Binary>

    /** Fire-and-forget — basis for non-acked send. */
    public suspend fun fireAndForget(route: PeerRoute, payload: Binary)

    override fun close(): Unit = Unit
}

/**
 * Factory for [PeerTransport] — concrete transports (RSocket, kotlinx-rpc, gRPC)
 * register one instance with [space.kscience.dataforge.context.Context] so callers can
 * resolve without knowing the underlying stack.
 */
public fun interface PeerTransportFactory {
    public suspend fun connect(address: Name, meta: Meta): PeerTransport
}

/** Convenience: connect with an empty [Meta] configuration. */
public suspend fun PeerTransportFactory.connect(address: Name): PeerTransport =
    connect(address, Meta.EMPTY)
