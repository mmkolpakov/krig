package space.kscience.krig.core.contracts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedAction
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedDevice
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.provider.Provider
import kotlin.time.Clock

/**
 * Logical unit of the control plane — hardware driver, digital twin, math model, or
 * orchestrator. Carries properties, actions, capabilities and reactive state; follows
 * Tango / Waltz vocabulary where `Device` is the universal logical unit.
 *
 * Also a [Provider] for introspection. Subclasses opt in via [UnstableKrigForSubclassing].
 */
@MustUseReturnValues
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface Device : ContextAware, Provider, AutoCloseable, TypedDevice, DeviceEnvironment {

    /** Background tasks launch here. `SupervisorJob`: a failed child does not cancel the device. */
    override val deviceScope: CoroutineScope

    override val name: Name

    public val meta: ObservableMeta

    /** Control-plane descriptors. Descriptors must match [propertySpec] registrations. */
    public val propertyDescriptors: Map<Name, PropertyDescriptor>

    /** Control-plane action descriptors. Descriptors must match [actionSpec] registrations. */
    public val actionDescriptors: Map<Name, ActionDescriptor>

    /**
     * Reads a property through the serialization boundary. Implementations may perform I/O and
     * emit [PropertyChangedMessage] on success.
     *
     * **Hot-path note.** This API allocates [Meta] on every call. The typed contract
     * ([reader] returning [TypedReader]) is the zero-allocation primary surface for
     * high-frequency reads. `readProperty(Name): Meta` is preserved for control-plane
     * operations: introspection, debug tools, and generic transports that must serialize.
     */
    @PerformancePitfall
    public suspend fun readProperty(propertyName: Name): Meta

    /** Writes a property through the serialization boundary. Prefer [writer] on the data plane. */
    @PerformancePitfall
    public suspend fun writeProperty(propertyName: Name, value: Meta)

    /** Executes an action through the serialization boundary. Prefer [executeOutcome] for value-based error handling. */
    @PerformancePitfall
    public suspend fun execute(actionName: Name, argument: Meta? = null): Meta?

    /** Control plane: lifecycle, faults, attach/detach. SUSPEND on buffer overflow — never dropped. */
    @InternalKrigApi
    public val controlFlow: Flow<MessageEnvelope<DeviceMessage>>

    /** Data plane: property and predicate changes. DROP_OLDEST on overflow. */
    @InternalKrigApi
    public val dataFlow: Flow<MessageEnvelope<DeviceMessage>>

    /** Cold merge of [controlFlow] and [dataFlow]. Each subscriber owns its back-pressure. */
    @InternalKrigApi
    public val messageFlow: Flow<MessageEnvelope<DeviceMessage>>
        get() = merge(controlFlow, dataFlow)

    /** Payload-only view for scripts and legacy consumers that do not need envelope context. */
    @InternalKrigApi
    public val messagePayloadFlow: Flow<DeviceMessage>
        get() = messageFlow.payloads()

    /**
     * Opens a principal-scoped subscription. Authorization is checked once at subscribe time;
     * subsequent elements flow without per-element overhead. Throws
     * [space.kscience.krig.api.services.AuthorizationException] on missing permission.
     */
    public suspend fun subscribe(principal: space.kscience.krig.api.context.Principal): Flow<MessageEnvelope<DeviceMessage>>

    /** Timestamping source; may be a virtual clock in simulations. */
    override val clock: Clock

    public val lifecycleState: LifecycleState

    public fun <C : Capability<*>> capability(key: CapabilityKey<C>): C?

    /** Suspends until capabilities are detached and this device scope has completed. */
    @OptIn(InternalKrigApi::class)
    public suspend fun shutdown() {
        cancelDeviceScopeSafely(name, deviceScope)
    }

    // --- Typed contract bridge ---

    /**
     * Fallback [TypedReader] derived from [readProperty] + [DevicePropertyContract.converter].
     * This crosses the `Meta` boundary and may allocate; drivers override it, or expose
     * [space.kscience.krig.core.contracts.typed.TypedBackend], for native typed access.
     */
    @OptIn(PerformancePitfall::class)
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        GenericTypedReader { spec.converter.read(readProperty(spec.name)) }

    /** Fallback [TypedWriter] — bridges through [writeProperty] + converter and may allocate. */
    @OptIn(PerformancePitfall::class)
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        GenericTypedWriter { value -> writeProperty(spec.name, spec.converter.convert(value)) }

    /** Default returns `null`; drivers opt-in by overriding to expose lock-free streaming. */
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Fallback [TypedAction] — bridges through [execute] + converters and may allocate. */
    @OptIn(PerformancePitfall::class)
    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        GenericTypedAction { input ->
            val resultMeta = execute(spec.name, spec.inputConverter.convert(input))
            resultMeta?.let(spec.outputConverter::read)
        }

    // --- Spec lookup (control-plane Meta boundary) ---

    /**
     * Returns a known [DevicePropertyContract] by name. Drivers backed by a
     * [DeviceBlueprint][space.kscience.krig.core.contracts.DeviceBlueprint] expose
     * registered specs so `readProperty(Name): Meta` callers can cross the serialization
     * boundary through the full operation pipeline (gates -> locks -> timeout -> retry ->
     * observers -> reader). Default returns `null`; `PipelineDevice` then applies
     * only generic gates before delegating to the underlying Meta operation.
     */
    public fun propertySpec(propertyName: Name): DevicePropertyContract<*>? = null

    /** Action analogue of [propertySpec]. */
    public fun actionSpec(
        actionName: Name,
    ): DeviceActionContract<*, *>? = null

    // --- Outcome-based API (errors as values) ---

    /**
     * Outcome wrapper of [readProperty]. Default routes through [runCatchingOperation]
     * (getOrThrow → re-wrap). Drivers that can produce [OperationOutcome] directly
     * (e.g. [DeviceBackend]-based) override this to avoid the double-wrap overhead;
     * override [readProperty] is NOT sufficient — callers using the outcome API
     * go through this method.
     */
    @OptIn(PerformancePitfall::class)
    public suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        captureOutcome { readProperty(propertyName) }

    /** Write-plane analogue of [readPropertyOutcome]. */
    @OptIn(PerformancePitfall::class)
    public suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        captureOutcome { writeProperty(propertyName, value) }

    /** Action-plane analogue of [readPropertyOutcome]. Drivers that can produce [OperationOutcome]
     * directly should override this to avoid the double-wrap overhead through [execute]. */
    @OptIn(PerformancePitfall::class)
    public suspend fun executeOutcome(actionName: Name, argument: Meta? = null): OperationOutcome<Meta?> =
        captureOutcome { execute(actionName, argument) }

    override fun content(target: String): Map<Name, Any> = emptyMap()

    /** Best-effort close: signals [deviceScope] cancellation. */
    @OptIn(InternalKrigApi::class)
    override fun close() {
        deviceScope.cancel("Device '${name}' closed")
    }
}

@OptIn(PerformancePitfall::class)
public suspend fun Device.readProperty(name: String): Meta = readProperty(name.asName())

@OptIn(PerformancePitfall::class)
public suspend fun Device.writeProperty(name: String, value: Meta): Unit =
    writeProperty(name.asName(), value)

@OptIn(PerformancePitfall::class)
public suspend fun Device.execute(name: String, argument: Meta? = null): Meta? = execute(name.asName(), argument)

public val Device.propertyNames: Set<Name> get() = propertyDescriptors.keys

public val Device.actionNames: Set<Name> get() = actionDescriptors.keys

@OptIn(InternalKrigApi::class)
private fun Device.markProgrammingFailure(cause: Throwable) {
    (this as? LifecycleStateHolder)?.updateLifecycleState(LifecycleState.Failed(cause))
}

private suspend inline fun <T> Device.captureOutcome(block: suspend () -> T): OperationOutcome<T> =
    try {
        runCatchingOperation { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        markProgrammingFailure(e)
        throw e
    }

/** Resolves the principal from the current coroutine context and delegates to [Device.subscribe]. */
public suspend fun Device.subscribeFromContext(): Flow<MessageEnvelope<DeviceMessage>> =
    subscribe(currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal)

/** Payload-only subscription helper for call sites that intentionally ignore envelope context. */
public suspend fun Device.subscribePayloadsFromContext(): Flow<DeviceMessage> =
    subscribeFromContext().payloads()
