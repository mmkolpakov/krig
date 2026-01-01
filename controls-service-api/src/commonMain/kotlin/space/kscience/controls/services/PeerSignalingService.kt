package space.kscience.controls.services

import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.context.ExecutionContext
import space.kscience.controls.core.messages.BinaryReadyNotification
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.meta.Meta
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A contract for a service that handles the signaling aspect of peer-to-peer binary data transfers.
 * This service is responsible for coordinating the transfer (e.g., notifying peers about available data)
 * but does not handle the bulk data transfer itself.
 */
public interface PeerSignalingService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Sends a request for binary data and awaits a notification that it's ready for transfer.
     *
     * @param address The network address of the target device.
     * @param contentId A unique identifier for the content being requested.
     * @param context The [ExecutionContext] for the operation.
     * @param timeout The maximum duration to wait for a response.
     * @return The [BinaryReadyNotification] from the target peer.
     */
    public suspend fun requestBinaryData(
        address: Address,
        contentId: String,
        context: ExecutionContext,
        timeout: Duration = 30.seconds,
    ): BinaryReadyNotification

    /**
     * Notifies a peer (or broadcasts) that a piece of binary data is ready for transfer.
     *
     * @param targetAddress The intended recipient. If null, it's a broadcast.
     * @param contentId A unique identifier for the content.
     * @param envelope An envelope containing metadata about the binary data.
     * @param context The [ExecutionContext] for the operation.
     */
    public suspend fun notifyBinaryReady(
        targetAddress: Address?,
        contentId: String,
        envelope: Envelope,
        context: ExecutionContext,
    )

    public companion object : PluginFactory<PeerSignalingService> {
        override val tag: PluginTag = PluginTag("peer.signaling", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): PeerSignalingService {
            error("PeerSignalingService is a service interface and requires a runtime-specific implementation.")
        }
    }
}