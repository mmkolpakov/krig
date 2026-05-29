@file:MustUseReturnValues

package space.kscience.krig.core.runtime

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.plus

/**
 * Envelope-oriented peer connection for content-addressable binary transfer.
 * Snapshots, files, offline state go through here. Flow-shaped, broker-less message
 * streams live in transport-adapter modules.
 */
public interface BinaryPeerConnection : AutoCloseable {
    public val peerId: Name

    /** Request a content blob by [contentId]. Returns `null` when absent. */
    public suspend fun receive(contentId: String): ByteArray?

    /** Ship [payload] tagged with [contentId] for later recall. */
    public suspend fun send(contentId: String, payload: ByteArray)

    override fun close(): Unit = Unit
}

/**
 * Routing prefix for [DefaultBinaryPeerConnection]. Sealed so the compiler enforces
 * the choice between a stable default and an explicitly named override; no free-form
 * strings on the public API.
 */
public sealed interface PeerRoutePrefix {
    public val name: Name

    public data object BinaryDefault : PeerRoutePrefix {
        override val name: Name = "krig.peer.binary".parseAsName()
    }

    public data class Custom(override val name: Name) : PeerRoutePrefix {
        init {
            require(name != Name.EMPTY) { "PeerRoutePrefix.Custom name must not be empty" }
        }
    }
}

/** Default [BinaryPeerConnection] over any [PeerTransport]. */
public class DefaultBinaryPeerConnection(
    private val transport: PeerTransport,
    private val routePrefix: PeerRoutePrefix = PeerRoutePrefix.BinaryDefault,
) : BinaryPeerConnection {
    public override val peerId: Name get() = transport.peerId

    public override suspend fun receive(
        contentId: String,
    ): ByteArray? = runCatching {
        transport.requestResponse(route(contentId), Binary.EMPTY).toByteArray()
    }.getOrNull()

    public override suspend fun send(
        contentId: String,
        payload: ByteArray,
    ) {
        transport.fireAndForget(route(contentId), payload.asBinary())
    }

    public override fun close(): Unit = transport.close()

    private fun route(contentId: String): PeerRoute =
        PeerRoute(routePrefix.name + contentId.asName())
}
