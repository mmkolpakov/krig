package space.kscience.controls.composite.ktor.peer

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.services.transport.PeerConnection
import space.kscience.controls.api.spec.QoS
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.meta.get
import kotlin.time.Duration

/**
 * A DataForge plugin that provides a Ktor TCP-based PeerConnection service.
 * It reads the host and port from its configuration meta to bind the server socket.
 */
public class KtorPeerPlugin(meta: Meta) : AbstractPlugin(meta) {
    override val tag: PluginTag get() = Companion.tag

    private val host: String by meta.string("0.0.0.0")
    private val port: Int = meta["port"].int ?: error("Port for KtorPeerPlugin is not defined")

//    private val connection: KtorTcpPeerConnection by lazy {
//        KtorTcpPeerConnection(context, host, port)
//    }

//    override val isConnected: StateFlow<Boolean> get() = connection.isConnected
//
//    override suspend fun connect(): Unit = connection.connect()
//    override suspend fun disconnect(): Unit = connection.disconnect()
//
//    override suspend fun receive(
//        address: Address,
//        contentId: String,
//        context: ExecutionContext,
//        timeout: Duration?,
//    ): Envelope? = connection.receive(address, contentId, context, timeout)
//
//    override suspend fun send(
//        address: Address,
//        contentId: String,
//        envelope: Envelope,
//        qos: QoS,
//        context: ExecutionContext,
//        timeout: Duration?,
//    ): Unit = connection.send(address, contentId, envelope, qos, context, timeout)

    override fun attach(context: Context) {
        super.attach(context)
//        context.launch { connect() }
    }

    override fun detach() {
//        context.launch { disconnect() }
        super.detach()
    }

    public companion object : PluginFactory<KtorPeerPlugin> {
        override val tag: PluginTag = PluginTag("peer.ktor.tcp", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): KtorPeerPlugin = KtorPeerPlugin(meta)
    }
}