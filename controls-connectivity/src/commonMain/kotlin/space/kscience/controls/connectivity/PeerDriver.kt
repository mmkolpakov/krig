package space.kscience.controls.connectivity

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A factory responsible for creating an instance of a [PeerConnection].
 *
 * @param P The type of the peer connection this driver creates.
 */
public fun interface PeerDriver<P : PeerConnection> {
    /**
     * Creates a new peer connection instance.
     * @param context The DataForge context for the connection.
     * @param meta The configuration meta for the connection.
     * @return A new instance of the peer connection.
     */
    public fun create(context: Context, meta: Meta): P
}