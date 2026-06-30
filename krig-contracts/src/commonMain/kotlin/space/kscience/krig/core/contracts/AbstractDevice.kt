package space.kscience.krig.core.contracts

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.map
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.KrigPerformancePitfall
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
@OptIn(InternalKrigApi::class, KrigPerformancePitfall::class)
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public abstract class AbstractDevice(
    override val name: Name,
    public val runtime: DeviceRuntime,
) : Device, LifecycleStateHolder, CapabilityHost, OperationTracker, GracefullyCloseable {

    private val operationController = OperationDrainController(name)

    /** DataForge context backing this device. */
    public val context: Context = runtime.context

    /** Messaging configuration captured for [subscribe]'s buffer sizing. */
    protected val messaging: DeviceMessaging = runtime.messaging

    override val clock: Clock = runtime.clock

    override val timeSource: TimeSource = runtime.timeSource

    override val deviceScope: CoroutineScope =
        CoroutineScope(
            context.coroutineContext +
                    SupervisorJob(context.coroutineContext[Job]) +
                    DeviceScopeElement(name),
        )

    /** Capability background scope: child of [deviceScope] (supervised), cancelled when it cancels. */
    override val capabilityScope: CoroutineScope =
        CoroutineScope(deviceScope.coroutineContext + SupervisorJob(deviceScope.coroutineContext[Job]))

    override val propertyDescriptors: Map<Name, PropertyDescriptor> = emptyMap()
    override val actionDescriptors: Map<Name, ActionDescriptor> = emptyMap()

    override val capabilityToggles: CapabilityToggles = CapabilityToggles()

    private val capabilityRegistry: CapabilityRegistry = CapabilityRegistry()

    final override val installedCapabilities: Collection<Capability<*>>
        get() = capabilityRegistry.installedCapabilities

    final override fun registerCapability(capability: Capability<*>) {
        capabilityRegistry.registerCapability(capability)
    }

    @InternalKrigApi
    final override fun <C : Capability<*>> getOrRegisterCapability(
        key: CapabilityKey<C>,
        factory: () -> C,
    ): C = capabilityRegistry.getOrRegisterCapability(key, factory)

    @InternalKrigApi
    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        capabilityRegistry.capability(key)

    // --- Operation API ---

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
        trackReentrant { block() }
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
        trackReentrant { block() }
    } catch (e: OperationFaultException) {
        properties.associateWith { OperationOutcome.Fail(e.fault) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        updateLifecycleState(LifecycleState.Failed(e))
        throw e
    }

    // --- Two-plane message flows (delegated to DeviceMessageBus) ---

    private val messageBus: DeviceMessageBus = DeviceMessageBus(name, deviceScope, messaging, runtime.hlc)

    // @InternalKrigApi is not inherited: re-annotate the overrides so the ABI filter hides them too.
    @InternalKrigApi
    final override val controlFlow: SharedFlow<DeviceMessageFrame<DeviceMessage>> get() = messageBus.controlFlow

    @InternalKrigApi
    final override val dataFlow: SharedFlow<DeviceMessageFrame<DeviceMessage>> get() = messageBus.dataFlow

    /** Suspending publish into the two-plane [DeviceMessageBus]; carries the ambient correlation id. */
    @InternalKrigApi
    protected suspend fun emit(message: DeviceMessage): Unit = messageBus.emit(message)

    @InternalKrigApi
    protected suspend fun emit(envelope: DeviceMessageFrame<DeviceMessage>): Unit = messageBus.emit(envelope)

    /** Non-suspending publish; returns `false` when the selected plane cannot accept the message now. */
    @InternalKrigApi
    protected fun tryEmit(message: DeviceMessage): Boolean = messageBus.tryEmit(message)

    @InternalKrigApi
    protected fun tryEmit(envelope: DeviceMessageFrame<DeviceMessage>): Boolean = messageBus.tryEmit(envelope)

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

    // --- Subscription with auth + audit (delegated to SubscriptionAuthorizer) ---

    private val subscriptions: SubscriptionAuthorizer = SubscriptionAuthorizer(name, context)

    /**
     * Authorization checked once at subscription time. Returned flow preserves the
     * source-side control/data-plane buffering policy. Consumers that prefer a best-effort
     * UI stream can add their own Flow buffer or use the higher-level subscription DSL.
     */
    override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> {
        subscriptions.authorizeSubscribe(principal)
        return messageFlow
    }

    override suspend fun subscribe(
        principal: Principal,
        property: Name,
    ): Flow<DeviceMessageFrame<DeviceMessage>> {
        subscriptions.authorizePropertySubscribe(principal, property)
        return messageFlow.filter { (it.payload as? PropertyChangedMessage)?.property == property }
    }

    override suspend fun shutdown() {
        shutdownSelf()
    }

    protected suspend fun shutdownSelf() {
        capabilityRegistry.detachOnce(this)
        // Close the bus before killing the pumps: producers suspended in emit() resume with
        // InvalidStateFault instead of hanging on a dead channel.
        messageBus.close()
        cancelDeviceScopeSafely(name, deviceScope)
    }

    /** Non-suspending close: closes the plane mailboxes and signals scope cancellation. */
    override fun close() {
        messageBus.close()
        deviceScope.cancel("Device '$name' closed")
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
