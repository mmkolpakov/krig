package space.kscience.controls.composite.ktor.peer

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.spec.QoS
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.core.contracts.ManagedComponent
import space.kscience.controls.connectivity.services.AddressResolver
import space.kscience.controls.api.addressing.TcpAddress
import space.kscience.controls.connectivity.PeerConnection
import space.kscience.controls.connectivity.PeerConnectionException
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.error
import space.kscience.dataforge.context.info
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.io.ByteArray
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.TaggedEnvelopeFormat
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.readWith
import space.kscience.dataforge.io.writeWith
import kotlin.time.Duration

private const val PEER_CONNECTION_PREFIX = "[PEER_CONNECTION]"

/**
 * A PeerConnection implementation using Ktor TCP sockets for direct binary data transfer.
 * It operates in a dual client/server mode: it listens for incoming connections to receive data
 * and initiates new connections to send data.
 */
public class KtorTcpPeerConnection(
    override val context: Context,
    private val serverHost: String,
    private val serverPort: Int,
) : PeerConnection, ManagedComponent, CoroutineScope by CoroutineScope(context.coroutineContext + SupervisorJob()) {

    private val clientSockets = mutableMapOf<TcpAddress, Socket>()
    private val socketsMutex = Mutex()

    private val addressResolver: AddressResolver? by lazy {
        context.plugins.find(inherit = true) { it is AddressResolver } as? AddressResolver
    }

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> get() = _isConnected.asStateFlow()

    override val lifecycleState: StateFlow<DeviceLifecycleState> = _isConnected.map {
        if (it) DeviceLifecycleState.Running else DeviceLifecycleState.Stopped
    }.stateIn(
        scope = this,
        started = SharingStarted.Eagerly,
        initialValue = if (_isConnected.value) DeviceLifecycleState.Running else DeviceLifecycleState.Stopped
    )

    private var serverJob: Job? = null
    private val incomingEnvelopes = MutableSharedFlow<Pair<String, Envelope>>()

    override suspend fun connect() {
        if (isConnected.value) {
            context.logger.info { "$PEER_CONNECTION_PREFIX Already connected on $serverHost:$serverPort" }
            return
        }
        context.logger.info { "$PEER_CONNECTION_PREFIX Starting TCP peer server on $serverHost:$serverPort" }
        val selectorManager = SelectorManager(currentCoroutineContext())
        val serverSocket = aSocket(selectorManager).tcp().bind(serverHost, serverPort)
        _isConnected.value = true

        serverJob = launch(currentCoroutineContext()) {
            try {
                while (isActive) {
                    val socket = serverSocket.accept()
                    launch {
                        context.logger.info { "$PEER_CONNECTION_PREFIX Accepted peer connection from ${socket.remoteAddress}" }
                        try {
                            val readChannel = socket.openReadChannel()
                            val contentIdLength = readChannel.readInt()
                            val contentId = readChannel.readPacket(contentIdLength).readText()
                            val bytes = readChannel.readRemaining().readByteArray()
                            val envelope = bytes.asBinary().readWith(TaggedEnvelopeFormat)
                            incomingEnvelopes.emit(contentId to envelope)
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                context.logger.error(e) { "$PEER_CONNECTION_PREFIX Error processing incoming peer data" }
                            }
                        } finally {
                            socket.close()
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    serverSocket.close()
                    _isConnected.value = false
                    context.logger.info { "$PEER_CONNECTION_PREFIX TCP peer server stopped." }
                }
            }
        }
    }

    override suspend fun disconnect() {
        serverJob?.cancelAndJoin()
        socketsMutex.withLock {
            clientSockets.values.forEach { it.close() }
            clientSockets.clear()
        }
        _isConnected.value = false
    }

    private suspend fun getOrConnectSocket(address: TcpAddress): Socket = socketsMutex.withLock {
        val existing = clientSockets[address]
        if (existing?.isClosed == false) {
            return@withLock existing
        }
        val selectorManager = SelectorManager(currentCoroutineContext())
        val newSocket = aSocket(selectorManager).tcp().connect(address.host, address.port)
        clientSockets[address] = newSocket
        return@withLock newSocket
    }

    override suspend fun receive(
        address: Address,
        contentId: String,
        context: ExecutionContext,
        timeout: Duration?,
    ): Envelope? = withTimeoutOrNull(timeout ?: Duration.INFINITE) {
        incomingEnvelopes.first { it.first == contentId }.second
    }

    override suspend fun send(
        address: Address,
        contentId: String,
        envelope: Envelope,
        qos: QoS,
        context: ExecutionContext,
        timeout: Duration?,
    ) {
        if (!isConnected.value) throw PeerConnectionException("Peer connection is not active.")

        val transportAddress = addressResolver?.resolve(address.route)
            ?: throw PeerConnectionException("AddressResolver not found in context or failed to resolve hubId '$address.hubId'.")

        val tcpAddress = (transportAddress as? TcpAddress)
            ?: throw PeerConnectionException("Resolved address for hubId '$address.hubId' is not a TCP address. Got: $transportAddress")

        withTimeout(timeout ?: Duration.INFINITE) {
            try {
                val socket = getOrConnectSocket(tcpAddress)
                val writeChannel = socket.openWriteChannel(autoFlush = true)
                val contentIdBytes = contentId.encodeToByteArray()
                writeChannel.writeInt(contentIdBytes.size)
                writeChannel.writeFully(contentIdBytes)
                val bytes = ByteArray { writeWith(TaggedEnvelopeFormat, envelope) }
                writeChannel.writeFully(bytes)
            } catch (e: Exception) {
                socketsMutex.withLock {
                    clientSockets.remove(tcpAddress)
                }
                throw PeerConnectionException("Failed to send binary data to hub '$address.hubId' at $tcpAddress", e)
            }
        }
    }
}