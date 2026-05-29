package space.kscience.krig.core.contracts

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.meta.ObservableMutableMeta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.identifiers.ControlsPermissions
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.envelope
import space.kscience.krig.api.messages.withHlcStamp
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.map
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Base [Device] implementation. Owns the two-plane message flows and the lifecycle state;
 * subclasses implement the protected `do*Outcome` hooks for I/O.
 */
@OptIn(InternalKrigApi::class, PerformancePitfall::class)
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public abstract class AbstractDevice(
    override val name: Name,
    public val runtime: DeviceRuntime,
) : Device, LifecycleStateHolder, CapabilityHost, OperationTracker, GracefullyCloseable {

    private val operationController = OperationDrainController(name)

    override val context: Context = runtime.context

    /** Messaging configuration captured for [subscribe]'s buffer sizing. */
    protected val messaging: DeviceMessaging = runtime.messaging

    override val meta: ObservableMeta = ObservableMutableMeta()

    override val clock: Clock = runtime.clock

    override val timeSource: TimeSource = runtime.timeSource

    override val deviceScope: CoroutineScope =
        CoroutineScope(
            context.coroutineContext +
                    SupervisorJob(context.coroutineContext[Job]) +
                    DeviceScopeElement(name),
        )

    override val propertyDescriptors: Map<Name, PropertyDescriptor> = emptyMap()
    override val actionDescriptors: Map<Name, ActionDescriptor> = emptyMap()

    override val capabilityToggles: CapabilityToggles = CapabilityToggles()

    private val capabilityRegistry: CapabilityRegistry = CapabilityRegistry()
    private val capabilityDetachLock = SynchronizedObject()
    private var capabilitiesDetached = false

    final override val installedCapabilities: Collection<Capability<*>>
        get() = capabilityRegistry.installedCapabilities

    final override fun registerCapability(capability: Capability<*>) {
        capabilityRegistry.registerCapability(capability)
    }

    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        capabilityRegistry.capability(key)

    // --- Operation API ---

    final override suspend fun readProperty(propertyName: Name): Meta =
        readPropertyOutcome(propertyName).getOrThrow()

    final override suspend fun writeProperty(propertyName: Name, value: Meta) {
        writePropertyOutcome(propertyName, value).getOrThrow()
    }

    final override suspend fun execute(actionName: Name, argument: Meta?): Meta? =
        executeOutcome(actionName, argument).getOrThrow()

    final override suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        trackedOperationOutcome { doReadPropertyOutcome(propertyName) }

    final override suspend fun writePropertyOutcome(
        propertyName: Name,
        value: Meta,
    ): OperationOutcome<Unit> =
        trackedOperationOutcome { doWritePropertyOutcome(propertyName, value) }

    final override suspend fun executeOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        trackedOperationOutcome { doExecuteOutcome(actionName, argument) }

    final override suspend fun readObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        trackedOperationOutcome { doReadObservedOutcome(propertyName) }

    final override suspend fun readBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        trackedBatchOutcome(properties) { doReadBatchOutcome(properties) }

    final override suspend fun readBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        trackedOperationOutcome { doReadBinaryOutcome(propertyName) }

    final override suspend fun readBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> =
        trackedBatchOutcome(properties) { doReadBatchBinaryOutcome(properties) }

    final override suspend fun writeBinaryOutcome(propertyName: Name, value: Binary): OperationOutcome<Unit> =
        trackedOperationOutcome { doWriteBinaryOutcome(propertyName, value) }

    final override suspend fun writeBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        trackedBatchOutcome(values.keys) { doWriteBatchOutcome(values) }

    protected open suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Fail(
            GenericOperationFault(message = "Read of property '$propertyName' is not implemented by device '$name'."),
        )

    protected open suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.Fail(
            GenericOperationFault(message = "Write of property '$propertyName' is not implemented by device '$name'."),
        )

    protected open suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Fail(
            GenericOperationFault(message = "Action '$actionName' is not implemented by device '$name'."),
        )

    protected open suspend fun doReadObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        doReadPropertyOutcome(propertyName).map { value ->
            ObservedValue(value = value, time = clock.now(), quality = DataQuality.GOOD)
        }

    protected open suspend fun doReadBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        properties.associateWith { propertyName -> doReadObservedOutcome(propertyName) }

    protected open suspend fun doReadBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        OperationOutcome.Fail(
            GenericOperationFault(message = "Binary read of property '$propertyName' is not implemented by device '$name'."),
        )

    protected open suspend fun doReadBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> =
        properties.associateWith { propertyName -> doReadBinaryOutcome(propertyName) }

    protected open suspend fun doWriteBinaryOutcome(propertyName: Name, value: Binary): OperationOutcome<Unit> =
        OperationOutcome.Fail(
            GenericOperationFault(message = "Binary write of property '$propertyName' is not implemented by device '$name'."),
        )

    protected open suspend fun doWriteBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        values.mapValues { (propertyName, value) -> doWritePropertyOutcome(propertyName, value) }

    private suspend inline fun <T> trackedOperationOutcome(
        crossinline block: suspend () -> OperationOutcome<T>,
    ): OperationOutcome<T> = try {
        enterOperation()
        try {
            block()
        } finally {
            exitOperation()
        }
    } catch (e: OperationFaultException) {
        OperationOutcome.Fail(e.fault)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        updateLifecycleState(LifecycleState.Failed(e))
        throw e
    }

    private suspend inline fun <T> trackedBatchOutcome(
        properties: Collection<Name>,
        crossinline block: suspend () -> Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> = try {
        enterOperation()
        try {
            block()
        } finally {
            exitOperation()
        }
    } catch (e: OperationFaultException) {
        properties.associateWith { OperationOutcome.Fail(e.fault) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        updateLifecycleState(LifecycleState.Failed(e))
        throw e
    }

    // --- Two-plane message flows ---

    private val mutableControlFlow: MutableSharedFlow<DeviceMessageEnvelope<DeviceMessage>> = MutableSharedFlow(
        replay = messaging.replay,
        extraBufferCapacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val mutableDataFlow: MutableSharedFlow<DeviceMessageEnvelope<DeviceMessage>> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = messaging.dataBufferCapacity,
        onBufferOverflow = messaging.toDataBufferOverflow(),
    )

    final override val controlFlow: SharedFlow<DeviceMessageEnvelope<DeviceMessage>> = mutableControlFlow.asSharedFlow()
    final override val dataFlow: SharedFlow<DeviceMessageEnvelope<DeviceMessage>> = mutableDataFlow.asSharedFlow()

    private val controlMailbox: Channel<DeviceMessageEnvelope<DeviceMessage>> = Channel(
        capacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val dataMailbox: Channel<DeviceMessageEnvelope<DeviceMessage>> = Channel(
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
        emit(message.envelope())
    }

    @InternalKrigApi
    protected suspend fun emit(envelope: DeviceMessageEnvelope<DeviceMessage>) {
        mailboxFor(envelope).send(envelope)
    }

    /**
     * Non-suspending variant. Returns `false` when the selected plane mailbox cannot accept
     * the message immediately. HLC stamping still happens in the plane pump so `tryEmit` and
     * suspending [emit] share one ordering point.
     */
    @InternalKrigApi
    protected fun tryEmit(message: DeviceMessage): Boolean {
        return tryEmit(message.envelope())
    }

    @InternalKrigApi
    protected fun tryEmit(envelope: DeviceMessageEnvelope<DeviceMessage>): Boolean {
        return mailboxFor(envelope).trySend(envelope).isSuccess
    }

    private suspend fun pumpMessages(
        mailbox: ReceiveChannel<DeviceMessageEnvelope<DeviceMessage>>,
        target: MutableSharedFlow<DeviceMessageEnvelope<DeviceMessage>>,
    ) {
        for (envelope in mailbox) {
            val stamped = runtime.hlc?.let { envelope.withHlcStamp(it.tick()) } ?: envelope
            target.emit(stamped)
        }
    }

    private fun mailboxFor(envelope: DeviceMessageEnvelope<DeviceMessage>): Channel<DeviceMessageEnvelope<DeviceMessage>> = when (
        envelope.payload
    ) {
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
     * Authorization checked once at subscription time. Returned flow preserves the
     * source-side control/data-plane buffering policy. Consumers that prefer a best-effort
     * UI stream can add their own Flow buffer or use the higher-level subscription DSL.
     */
    override suspend fun subscribe(principal: Principal): Flow<DeviceMessageEnvelope<DeviceMessage>> {
        val permission = ControlsPermissions.deviceSubscribe(name.toString())
        context.authorizationService.checkPermission(principal, permission)
        if (context.auditService.isActive) {
            context.auditService.record(
                principal,
                AuditAction.DeviceSubscribe,
                Meta { OperationFaultDetails.DEVICE put name.toString() },
            )
        }
        return messageFlow
    }

    override suspend fun shutdown() {
        shutdownSelf()
    }

    protected suspend fun shutdownSelf() {
        detachCapabilitiesOnce()
        cancelDeviceScopeSafely(name, deviceScope)
    }

    private fun claimCapabilityDetach(): Boolean = synchronized(capabilityDetachLock) {
        if (capabilitiesDetached) {
            false
        } else {
            capabilitiesDetached = true
            true
        }
    }

    private suspend fun detachCapabilitiesOnce() {
        if (!claimCapabilityDetach()) return
        for (capability in installedCapabilities.toList().asReversed()) {
            ignoreCleanupFailureSuspending {
                context(this@AbstractDevice as CapabilityHost) { capability.onDetach() }
            }
        }
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
