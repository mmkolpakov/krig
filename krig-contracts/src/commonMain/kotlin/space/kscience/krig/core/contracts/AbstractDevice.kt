package space.kscience.krig.core.contracts

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.meta.ObservableMutableMeta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.identifiers.ControlsPermissions
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.DeviceCapability
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Devices that own a runtime capability registry. [AbstractDevice] implements it directly;
 * wrappers may implement it to expose their installed capabilities without forcing callers to
 * know where the registry lives.
 */
@InternalKrigApi
public interface RuntimeCapabilityHost {
    public val installedCapabilities: Collection<DeviceCapability<*>>

    public fun installCapability(capability: DeviceCapability<*>)
}

/**
 * Base [Device] implementation. Owns the two-plane message flows and the lifecycle state;
 * subclasses implement [readProperty], [writeProperty], [execute] and optionally override
 * [children] for composite devices.
 */
@OptIn(InternalKrigApi::class, PerformancePitfall::class)
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public abstract class AbstractDevice(
    override val name: Name,
    public val runtime: DeviceRuntime,
) : Device, LifecycleStateHolder, RuntimeCapabilityHost, HasCapabilityToggler, OperationTracker, GracefullyCloseable {

    private val operationController = GracefulOperationController(name)

    override val context: Context = runtime.context

    /** Messaging configuration captured for [subscribe]'s buffer sizing. */
    protected val messaging: DeviceMessaging = runtime.messaging

    override val meta: ObservableMeta = ObservableMutableMeta()

    override val clock: Clock = runtime.clock

    override val timeSource: TimeSource = runtime.timeSource

    override val deviceScope: CoroutineScope =
        CoroutineScope(context.coroutineContext + SupervisorJob(context.coroutineContext[Job]))

    override val propertyDescriptors: Map<Name, PropertyDescriptor> = emptyMap()
    override val actionDescriptors: Map<Name, ActionDescriptor> = emptyMap()

    @InternalKrigApi
    override val toggler: CapabilityToggler = CapabilityToggler()

    private val runtimeCapabilities: MutableMap<CapabilityKey<*, *>, DeviceCapability<*>> = mutableMapOf()

    @InternalKrigApi
    final override val installedCapabilities: Collection<DeviceCapability<*>>
        get() = runtimeCapabilities.values

    @InternalKrigApi
    final override fun installCapability(capability: DeviceCapability<*>) {
        runtimeCapabilities[capability.key] = capability
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DeviceCapability<*>> capability(key: CapabilityKey<C, *>): C? =
        runtimeCapabilities[key] as? C

    // --- Two-plane message flows ---

    private val mutableControlFlow: MutableSharedFlow<DeviceMessage> = MutableSharedFlow(
        replay = messaging.replay,
        extraBufferCapacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val mutableDataFlow: MutableSharedFlow<DeviceMessage> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = messaging.dataBufferCapacity,
        onBufferOverflow = messaging.toDataBufferOverflow(),
    )

    final override val controlFlow: SharedFlow<DeviceMessage> = mutableControlFlow.asSharedFlow()
    final override val dataFlow: SharedFlow<DeviceMessage> = mutableDataFlow.asSharedFlow()

    private val controlMailbox: Channel<DeviceMessage> = Channel(
        capacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val dataMailbox: Channel<DeviceMessage> = Channel(
        capacity = mailboxCapacity(messaging.dataBufferCapacity, messaging.toDataBufferOverflow()),
        onBufferOverflow = messaging.toDataBufferOverflow(),
    )

    init {
        deviceScope.launch { pumpMessages(controlMailbox, mutableControlFlow) }
        deviceScope.launch { pumpMessages(dataMailbox, mutableDataFlow) }
    }

    /**
     * Routes by message type into a per-plane mailbox. A single pump per plane applies the
     * optional HLC stamp immediately before publishing to the shared flow, preserving
     * monotonic stamp order within that plane while keeping slow control subscribers from
     * blocking data-plane publication (and vice versa).
     */
    @InternalKrigApi
    protected suspend fun emit(message: DeviceMessage) {
        mailboxFor(message).send(message)
    }

    /**
     * Non-suspending variant. Returns `false` when the selected plane mailbox cannot accept
     * the message immediately. HLC stamping still happens in the plane pump so `tryEmit` and
     * suspending [emit] share one ordering point.
     */
    @InternalKrigApi
    protected fun tryEmit(message: DeviceMessage): Boolean {
        return mailboxFor(message).trySend(message).isSuccess
    }

    private suspend fun pumpMessages(
        mailbox: ReceiveChannel<DeviceMessage>,
        target: MutableSharedFlow<DeviceMessage>,
    ) {
        for (message in mailbox) {
            val stamped = runtime.hlc?.let { message.withHlcStamp(it.tick()) } ?: message
            target.emit(stamped)
        }
    }

    private fun mailboxFor(message: DeviceMessage): Channel<DeviceMessage> = when (message) {
        is PropertyChangedMessage -> dataMailbox
        else -> controlMailbox // errors, lifecycle, attach/detach, faults — never drop
    }

    private fun mailboxCapacity(capacity: Int, overflow: BufferOverflow): Int = when (overflow) {
        BufferOverflow.SUSPEND -> capacity
        BufferOverflow.DROP_OLDEST, BufferOverflow.DROP_LATEST -> maxOf(1, capacity)
    }

    // --- Lifecycle state ---

    private val mutableLifecycleStateFlow: MutableStateFlow<LifecycleState> =
        MutableStateFlow(LifecycleState.Detached)

    /**
     * Observable lifecycle state. Starts in [LifecycleState.Detached] — safe-by-default;
     * the always-on typed lifecycle gates reject reads/writes/actions in this state
     * until a LifecycleCapability attaches and promotes the device.
     */
    override val lifecycleStateFlow: StateFlow<LifecycleState> = mutableLifecycleStateFlow.asStateFlow()

    override val lifecycleState: LifecycleState get() = mutableLifecycleStateFlow.value

    @InternalKrigApi
    override fun updateLifecycleState(state: LifecycleState) {
        mutableLifecycleStateFlow.value = state
    }

    // --- Subscription with auth + audit ---

    /**
     * Authorization checked once at subscription time. Returned flow is buffered with
     * DROP_OLDEST so a slow external consumer drops its own messages instead of blocking
     * the hardware polling loop. For guaranteed delivery route through a persistent broker.
     */
    override suspend fun subscribe(principal: Principal): Flow<DeviceMessage> {
        val permission = ControlsPermissions.deviceSubscribe(name.toString())
        context.authorizationService.checkPermission(principal, permission)
        if (context.auditService.isActive) {
            context.auditService.record(
                principal,
                AuditAction.DeviceSubscribe,
                Meta { "device" put name.toString() },
            )
        }
        return messageFlow.buffer(
            capacity = messaging.controlBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    override suspend fun shutdown() {
        shutdownChildren()
        shutdownSelf()
    }

    protected open suspend fun shutdownChildren() {
        supervisorScope {
            val jobs = children.values.map { child ->
                async {
                    ignoreCleanupFailureSuspending { child.shutdown() }
                }
            }
            jobs.awaitAll()
        }
    }

    protected suspend fun shutdownSelf() {
        cancelDeviceScopeSafely(name, deviceScope)
    }

    @InternalKrigApi
    override fun enterOperation(): Unit = operationController.enterOperation()

    @InternalKrigApi
    override fun exitOperation(): Unit = operationController.exitOperation()

    /**
     * Waits up to [drainTimeout] for in-flight operations, then performs suspending
     * [Device.shutdown].
     */
    override suspend fun closeGracefully(drainTimeout: Duration) {
        closeGracefullyUsing(drainTimeout) { shutdown() }
    }

    protected suspend fun closeGracefullyUsing(
        drainTimeout: Duration,
        shutdownBlock: suspend () -> Unit,
    ) {
        operationController.closeGracefully(drainTimeout, shutdownBlock)
    }
}
