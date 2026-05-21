@file:MustUseReturnValues

package space.kscience.krig.core.runtime

/**
 * Envelope-oriented peer connection for content-addressable binary transfer.
 * Snapshots, files, offline state go through here. Flow-shaped, broker-less message
 * streams live in transport-adapter modules.
 */
public interface BinaryPeerConnection : AutoCloseable {
    public val peerId: String

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
    public val literal: String

    public data object BinaryDefault : PeerRoutePrefix {
        override val literal: String = "krig.peer.binary"
    }

    public data class Custom(override val literal: String) : PeerRoutePrefix {
        init {
            require(literal.isNotBlank()) { "PeerRoutePrefix.Custom literal must not be blank" }
        }
    }
}

/** Default [BinaryPeerConnection] over any [PeerTransport]. */
public class DefaultBinaryPeerConnection(
    private val transport: PeerTransport,
    private val routePrefix: PeerRoutePrefix = PeerRoutePrefix.BinaryDefault,
) : BinaryPeerConnection {
    public override val peerId: String get() = transport.peerId

    public override suspend fun receive(
        contentId: String,
    ): ByteArray? = runCatching {
        transport.requestResponse("${routePrefix.literal}/$contentId", ByteArray(0))
    }.getOrNull()

    public override suspend fun send(
        contentId: String,
        payload: ByteArray,
    ) {
        transport.fireAndForget("${routePrefix.literal}/$contentId", payload)
    }

    public override fun close(): Unit = transport.close()
}
