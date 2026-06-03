package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.provider.Provider
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.map
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedDevice
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
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

    /** Control plane: lifecycle, faults, attach/detach. SUSPEND on buffer overflow — never dropped. */
    @InternalKrigApi
    public val controlFlow: Flow<DeviceMessageFrame<DeviceMessage>>

    /** Data plane: property and predicate changes. DROP_OLDEST on overflow. */
    @InternalKrigApi
    public val dataFlow: Flow<DeviceMessageFrame<DeviceMessage>>

    /** Cold merge of [controlFlow] and [dataFlow]. Each subscriber owns its back-pressure. */
    @InternalKrigApi
    public val messageFlow: Flow<DeviceMessageFrame<DeviceMessage>>
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
    public suspend fun subscribe(principal: space.kscience.krig.api.context.Principal): Flow<DeviceMessageFrame<DeviceMessage>>

    /**
     * Property-granular subscription: authorizes [principal] for [property] specifically and streams
     * only that property's [PropertyChangedMessage]s. The default keeps the device-wide [subscribe]
     * authorization and filters the stream; implementations with an authorization service (see
     * `AbstractDevice`) override this with a per-property ACL that also accepts a property-scoped
     * grant. Throws [space.kscience.krig.api.services.AuthorizationException] on missing permission.
     */
    public suspend fun subscribe(
        principal: space.kscience.krig.api.context.Principal,
        property: Name,
    ): Flow<DeviceMessageFrame<DeviceMessage>> =
        subscribe(principal).filter { (it.payload as? PropertyChangedMessage)?.property == property }

    /** Timestamping source; may be a virtual clock in simulations. */
    override val clock: Clock

    public val lifecycleState: LifecycleState

    @InternalKrigApi
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
        TypedReader { spec.converter.read(readProperty(spec.name)) }

    /** Fallback [TypedWriter] — bridges through [writeProperty] + converter and may allocate. */
    @OptIn(PerformancePitfall::class)
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        TypedWriter { value -> writeProperty(spec.name, spec.converter.convert(value)) }

    /** Default returns `null`; drivers opt-in by overriding to expose lock-free streaming. */
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Fallback [TypedAction] — bridges through [execute] + converters and may allocate. */
    @OptIn(PerformancePitfall::class)
    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        TypedAction { input ->
            val resultMeta = execute(spec.name, spec.inputConverter.convert(input))
            resultMeta?.let(spec.outputConverter::read)
        }

    // --- Spec lookup (control-plane Meta boundary) ---

    /**
     * Returns a known [DevicePropertyContract] by name. Drivers backed by a
     * [DeviceManifest][space.kscience.krig.core.contracts.DeviceManifest] expose
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

    /** Primary read API: predictable hardware/protocol failures are returned as values. */
    public suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta>

    /** Write-plane analogue of [readPropertyOutcome]. */
    public suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit>

    /** Action-plane analogue of [readPropertyOutcome]. */
    public suspend fun executeOutcome(actionName: Name, argument: Meta? = null): OperationOutcome<Meta?>

    /** Read API that preserves protocol/device data quality instead of forcing every success to GOOD. */
    public suspend fun readObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        readPropertyOutcome(propertyName).map { value ->
            ObservedValue(value = value, time = clock.now(), quality = DataQuality.GOOD)
        }

    /**
     * Batch read surface used by acquisition loops. Default is conservative and preserves
     * semantics by falling back to one [readObservedOutcome] per property.
     */
    public suspend fun readBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        properties.associateWith { propertyName -> readObservedOutcome(propertyName) }

    /** Opaque binary read path using DataForge [Binary]. */
    public suspend fun readBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Device '$name' does not support binary read for property '$propertyName'.",
            ),
        )

    /** ByteArray convenience over [readBinaryOutcome]. */
    public suspend fun readBytesOutcome(propertyName: Name): OperationOutcome<ByteArray> =
        readBinaryOutcome(propertyName).map { binary -> binary.toByteArray() }

    /** Binary batch read. Default is sequential; drivers override it for block reads. */
    public suspend fun readBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> =
        properties.associateWith { propertyName -> readBinaryOutcome(propertyName) }

    /**
     * Batch write surface. Default is sequential and not atomic. Backends that write one
     * physical transaction should report a whole-transaction failure as the same failure
     * for every requested property when individual statuses are unavailable.
     */
    public suspend fun writeBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        values.mapValues { (propertyName, value) -> writePropertyOutcome(propertyName, value) }

    /** Binary write path for payloads that should not be forced through Meta. */
    public suspend fun writeBinaryOutcome(propertyName: Name, value: Binary): OperationOutcome<Unit> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Device '$name' does not support binary write for property '$propertyName'.",
            ),
        )

    override fun content(target: String): Map<Name, Any> = emptyMap()

    /** Best-effort close: signals [deviceScope] cancellation. */
    @OptIn(InternalKrigApi::class)
    override fun close() {
        deviceScope.cancel("Device '${name}' closed")
    }
}

/**
 * Reads a property through the serialization boundary; convenience over [Device.readPropertyOutcome]
 * that throws on failure. Allocates [Meta] per call — typed readers avoid that boundary on the hot path.
 * Kept as an extension (not a member) to signal it is derived sugar, not part of the overridable contract.
 */
@PerformancePitfall
public suspend fun Device.readProperty(propertyName: Name): Meta =
    readPropertyOutcome(propertyName).getOrThrow()

/** Writes a property through the serialization boundary; throwing convenience over [Device.writePropertyOutcome]. */
@PerformancePitfall
public suspend fun Device.writeProperty(propertyName: Name, value: Meta) {
    writePropertyOutcome(propertyName, value).getOrThrow()
}

/** Executes an action through the serialization boundary; throwing convenience over [Device.executeOutcome]. */
@PerformancePitfall
public suspend fun Device.execute(actionName: Name, argument: Meta? = null): Meta? =
    executeOutcome(actionName, argument).getOrThrow()

@OptIn(PerformancePitfall::class)
public suspend fun Device.readPropertyPath(path: String): Meta = readProperty(path.parseAsName())

@OptIn(PerformancePitfall::class)
public suspend fun Device.readPropertyId(id: String): Meta = readProperty(id.asName())

@OptIn(PerformancePitfall::class)
public suspend fun Device.writePropertyPath(path: String, value: Meta): Unit =
    writeProperty(path.parseAsName(), value)

@OptIn(PerformancePitfall::class)
public suspend fun Device.writePropertyId(id: String, value: Meta): Unit =
    writeProperty(id.asName(), value)

@OptIn(PerformancePitfall::class)
public suspend fun Device.executePath(path: String, argument: Meta? = null): Meta? =
    execute(path.parseAsName(), argument)

@OptIn(PerformancePitfall::class)
public suspend fun Device.executeId(id: String, argument: Meta? = null): Meta? =
    execute(id.asName(), argument)

public val Device.propertyNames: Set<Name> get() = propertyDescriptors.keys

public val Device.actionNames: Set<Name> get() = actionDescriptors.keys

/** Resolves the principal from the current coroutine context and delegates to [Device.subscribe]. */
public suspend fun Device.subscribeFromContext(): Flow<DeviceMessageFrame<DeviceMessage>> =
    subscribe(currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal)

/** Payload-only subscription helper for call sites that intentionally ignore envelope context. */
public suspend fun Device.subscribePayloadsFromContext(): Flow<DeviceMessage> =
    subscribeFromContext().payloads()
