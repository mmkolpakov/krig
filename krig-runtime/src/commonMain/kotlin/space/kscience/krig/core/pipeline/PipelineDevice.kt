@file:Suppress("RemoveRedundantQualifierName")
@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import space.kscience.attributes.Attributes
import space.kscience.dataforge.io.Binary
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.timeout
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.OperationTracker
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.contracts.CapabilityRegistry
import space.kscience.krig.core.contracts.CapabilityToggles
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.EmptyDeviceNodeChildren

/**
 * Typed-primary pipeline decorator. Wraps a [delegate] [Device] so every
 * `reader(spec)` / `writer(spec)` / `execute(...)` call goes through the declarative
 * operation QoS pipeline.
 *
 * Cross-cutting concerns (gates, observers, timeout, retry, resource locks) are configured
 * via the spec data classes — adding a new concern is an additive `data class` field, not
 * a new chain-control interface.
 *
 * Constructor is `@InternalKrigApi`; assemble through [wrapWithPipeline]
 * which wires per-device defaults (lifecycle, RBAC, audit, latency-budget).
 */
public class PipelineDevice @InternalKrigApi constructor(
    @property:InternalKrigApi public val delegate: Device,
    private val operationSpecs: Map<OperationKind, OperationPipelineSpec> = emptyMap(),
    private val readDecorators: List<ReadDecorator> = emptyList(),
    private val registry: ResourceLockRegistry = ResourceLockRegistry(),
    @property:InternalKrigApi public val capabilities: Attributes = Attributes.EMPTY,
) : Device by delegate, LifecycleStateHolder, CapabilityHost, DeviceNode {
    private val cacheLock = SynchronizedObject()
    private val readerCache = mutableMapOf<Name, Lazy<CachedReader>>()
    private val writerCache = mutableMapOf<Name, Lazy<CachedWriter>>()
    private val actionCache = mutableMapOf<Name, Lazy<CachedAction>>()
    private val capabilityRegistry = CapabilityRegistry()
    private val detachLock = SynchronizedObject()
    private var detached = false
    private val readExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Read)
    }
    private val writeExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Write)
    }
    private val actionPipelineExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Action)
    }

    override val device: Device get() = this

    override val children: Map<Name, DeviceNode>
        get() = (delegate as? DeviceNode)?.children.orEmpty()

    override val childrenFlow: StateFlow<Map<Name, DeviceNode>>
        get() = (delegate as? DeviceNode)?.childrenFlow ?: EmptyDeviceNodeChildren

    override fun content(target: String): Map<Name, Any> =
        if (target == defaultTarget) children else delegate.content(target)

    override val capabilityToggles: CapabilityToggles =
        (delegate as? CapabilityHost)?.capabilityToggles ?: CapabilityToggles()

    override val lifecycleStateFlow: StateFlow<LifecycleState>?
        get() = (delegate as? LifecycleStateHolder)?.lifecycleStateFlow

    override fun updateLifecycleState(state: LifecycleState) {
        (delegate as? LifecycleStateHolder)?.updateLifecycleState(state)
    }

    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        directCapabilities().firstCapability(key)
            ?: capabilityRegistry.capability(key)
            ?: delegate.capability(key)

    override val installedCapabilities: Collection<Capability<*>>
        get() = (
                directCapabilities() +
                        capabilityRegistry.installedCapabilities +
                        (delegate as? CapabilityHost)?.installedCapabilities.orEmpty()
                ).distinctBy { it.key.id }

    override fun registerCapability(capability: Capability<*>) {
        capabilityRegistry.registerCapability(capability)
    }

    override fun close() {
        deviceScope.launch(start = CoroutineStart.UNDISPATCHED) { detachCapabilitiesOnce() }
        delegate.close()
    }

    override suspend fun shutdown() {
        detachCapabilitiesOnce()
        delegate.shutdown()
    }

    private fun claimDetach(): Boolean = synchronized(detachLock) {
        if (detached) {
            false
        } else {
            detached = true
            true
        }
    }

    private suspend fun detachCapabilitiesOnce() {
        if (!claimDetach()) return
        val ownedCapabilities = (directCapabilities() + capabilityRegistry.installedCapabilities)
            .distinctBy { it.key.id }
            .asReversed()
        for (capability in ownedCapabilities) {
            ignoreCleanupFailureSuspending {
                context(this@PipelineDevice as CapabilityHost) { capability.onDetach() }
            }
        }
    }

    private fun directCapabilities(): Collection<Capability<*>> =
        capabilities.content.values.filterIsInstance<Capability<*>>()

    private fun operationSpec(kind: OperationKind): OperationPipelineSpec =
        operationSpecs[kind] ?: OperationPipelineSpec.Empty

    private fun compileSharedExecutor(
        kind: OperationKind,
    ): suspend (OperationPlan, Any?) -> OperationOutcome<Any?> {
        val opSpec = operationSpec(kind)
        return compileOperationExecutor(
            gates = opSpec.gates,
            observers = opSpec.observers,
            registry = registry,
            timeSource = timeSource,
        )
    }

    private fun operationPolicy(
        descriptor: OperationDescriptor,
        opSpec: OperationPipelineSpec,
    ): OperationPolicy =
        OperationPolicy(
            timeout = descriptor.timeout ?: opSpec.defaultTimeout,
            retry = descriptor.retryPolicy ?: opSpec.defaultRetry,
            locks = descriptor.requiredLocks,
        )

    // --- Typed contract: compile pipeline ONCE per reader/writer --------------------

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> {
        val slot = synchronized(cacheLock) {
            readerCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedReader(
                        descriptor = spec.descriptor,
                        converter = spec.converter,
                        reader = compileReader(spec),
                    )
                }
            }
        }
        return slot.value.readerFor(spec)
    }

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        val slot = synchronized(cacheLock) {
            writerCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedWriter(
                        descriptor = spec.descriptor,
                        converter = spec.converter,
                        writer = compileWriter(spec),
                    )
                }
            }
        }
        return slot.value.writerFor(spec)
    }

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        delegate.sampler(spec)

    override suspend fun <T> readOutcome(spec: DevicePropertyContract<T>): OperationOutcome<T> {
        val compiled = reader(spec)
        return compiled.outcomeReaderOrNull()?.readOutcome()
            ?: catchingOperationOutcome { compiled.read() }
    }

    override suspend fun <T> writeOutcome(
        spec: MutableDevicePropertyContract<T>,
        value: T,
    ): OperationOutcome<Unit> {
        val compiled = writer(spec)
        return compiled.outcomeWriterOrNull()?.writeOutcome(value)
            ?: catchingOperationOutcome { compiled.write(value) }
    }

    override suspend fun <I, O> executeOutcome(
        spec: DeviceActionContract<I, O>,
        input: I,
    ): OperationOutcome<O?> {
        val compiled = action(spec)
        return compiled.outcomeActionOrNull()?.executeOutcome(input)
            ?: catchingOperationOutcome { compiled.execute(input) }
    }

    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> {
        val executor = actionExecutor(spec.asAnyActionContract()).castActionExecutor<I, O>()
        return object : OutcomeTypedAction<I, O> {
            override suspend fun executeOutcome(input: I): OperationOutcome<O?> =
                catchingOperationOutcome { executor(input) }

            override suspend fun execute(input: I): O? = executeOutcome(input).getOrThrow()
        }
    }

    private fun <T> compileReader(spec: DevicePropertyContract<T>): TypedReader<T> {
        val raw = delegate.reader(spec)
        val opSpec = operationSpec(OperationKinds.Read)
        val context = OperationContext(OperationKinds.Read, spec.name, spec.descriptor, name)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val decorated = readDecorators.fold(raw) { acc, dec -> dec.decorate(spec, acc) }
        val plan = OperationPlan(context, policy) {
            catchingOperationOutcome { decorated.read() }
        }
        val tracker = delegate as? OperationTracker
        return object : OutcomeTypedReader<T> {
            override suspend fun readOutcome(): OperationOutcome<T> =
                trackedOperation(tracker) {
                    readExecutor(plan, Unit).castOutcome()
                }

            override suspend fun read(): T = readOutcome().getOrThrow()
        }
    }

    private fun <T> compileWriter(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        val raw = delegate.writer(spec)
        val opSpec = operationSpec(OperationKinds.Write)
        val context = OperationContext(OperationKinds.Write, spec.name, spec.descriptor, name)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val tracker = delegate as? OperationTracker
        val plan = OperationPlan(context, policy) { value ->
            catchingOperationOutcome { raw.write(value.castPayload()) }
        }
        return object : OutcomeTypedWriter<T> {
            override suspend fun writeOutcome(value: T): OperationOutcome<Unit> =
                trackedOperation(tracker) {
                    writeExecutor(plan, value).castOutcome()
                }

            override suspend fun write(value: T) {
                writeOutcome(value).getOrThrow()
            }
        }
    }

    // --- Control-plane Meta boundary: route through the operation pipeline when spec is known --
    //
    // When the delegate registers a [DevicePropertyContract] / [DeviceActionContract] for the
    // requested name (typically through [DeviceManifest]), the serialization-facing
    // Meta API crosses into the typed executor so timeout / retry / locks / observers
    // all fire. Unknown names fail fast: the core runtime no longer fabricates synthetic
    // specs for unregistered Meta calls.

    override suspend fun readProperty(propertyName: Name): Meta =
        readPropertyOutcome(propertyName).getOrThrow()

    override suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> {
        try {
            val spec = delegate.propertySpec(propertyName)
            if (spec != null) {
                val typedSpec = spec.asAnyPropertyContract()
                return when (val typed = catchingOperationOutcome { reader(typedSpec).read() }) {
                    is OperationOutcome.Fail -> typed
                    is OperationOutcome.Ok -> encodeControlPlaneMeta(typedSpec.converter, typed.value, "property", propertyName)
                }
            }
            return unknownProperty(propertyName, "read")
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    override suspend fun readObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        pipelinedSingleRead(propertyName, "read observed") {
            delegate.readObservedOutcome(propertyName)
        }

    override suspend fun readBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        pipelinedSingleRead(propertyName, "read binary") {
            delegate.readBinaryOutcome(propertyName)
        }

    override suspend fun readBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        pipelinedBatchRead(properties, OperationNames.BatchRead) { names ->
            delegate.readBatchOutcome(names)
        }

    override suspend fun readBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> =
        pipelinedBatchRead(properties, OperationNames.BatchReadBinary) { names ->
            delegate.readBatchBinaryOutcome(names)
        }

    override suspend fun writeBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        pipelinedBatchWrite(values)

    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        writePropertyOutcome(propertyName, value).getOrThrow()
    }

    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> {
        try {
            val spec = delegate.propertySpec(propertyName) as? MutableDevicePropertyContract<*>
            if (spec != null) {
                val mutable = spec.asAnyMutablePropertyContract()
                return when (val decoded = decodeControlPlaneMeta(mutable.converter, value, "property", propertyName)) {
                    is OperationOutcome.Fail -> decoded
                    is OperationOutcome.Ok -> catchingOperationOutcome { writer(mutable).write(decoded.value) }
                }
            }
            return unknownProperty(propertyName, "write")
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    override suspend fun execute(actionName: Name, argument: Meta?): Meta? =
        executeOutcome(actionName, argument).getOrThrow()

    override suspend fun executeOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> {
        try {
            val spec = delegate.actionSpec(actionName)
                ?: return unknownAction(actionName)
            return executeControlPlaneAction(spec.asAnyActionContract(), actionName, argument)
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    private suspend fun executeControlPlaneAction(
        spec: DeviceActionContract<Any?, Any?>,
        actionName: Name,
        argument: Meta?,
    ): OperationOutcome<Meta?> {
        val decoded = if (argument != null) {
            decodeControlPlaneMeta(spec.inputConverter, argument, "action", actionName)
        } else {
            OperationOutcome.Ok(null)
        }
        if (decoded is OperationOutcome.Fail) return decoded

        val input = (decoded as OperationOutcome.Ok).value
        return when (val result = catchingOperationOutcome { actionExecutor(spec)(input) }) {
            is OperationOutcome.Fail -> result
            is OperationOutcome.Ok -> encodeActionResult(spec, actionName, result.value)
        }
    }

    private fun encodeActionResult(
        spec: DeviceActionContract<Any?, Any?>,
        actionName: Name,
        value: Any?,
    ): OperationOutcome<Meta?> =
        if (value == null) {
            OperationOutcome.Ok(null)
        } else {
            encodeControlPlaneMeta(spec.outputConverter, value, "action", actionName)
        }

    private fun actionExecutor(spec: DeviceActionContract<Any?, Any?>): suspend (Any?) -> Any? {
        val slot = synchronized(cacheLock) {
            actionCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedAction(
                        descriptor = spec.descriptor,
                        inputConverter = spec.inputConverter,
                        outputConverter = spec.outputConverter,
                        executor = compileAction(spec),
                    )
                }
            }
        }
        // Compatibility is checked before the erased executor leaves the cache boundary.
        // If a caller reuses the same action name with another descriptor/converter set,
        // fail here rather than later inside the coroutine pipeline.
        return slot.value.executorFor(spec)
    }

    private fun <I, O> compileAction(spec: DeviceActionContract<I, O>): suspend (I) -> O? {
        val opSpec = operationSpec(OperationKinds.Action)
        val context = OperationContext(OperationKinds.Action, spec.name, spec.descriptor, name)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val tracker = delegate as? OperationTracker
        val plan = OperationPlan(context, policy) { input ->
            catchingOperationOutcome {
                val argMeta = if (input != null) spec.inputConverter.convert(input.castPayload()) else null
                val resultMeta = delegate.execute(spec.name, argMeta)
                if (resultMeta != null) spec.outputConverter.read(resultMeta) else null
            }
        }
        return { input ->
            val outcome = trackedOperation(tracker) { actionPipelineExecutor(plan, input).castOutcome<O?>() }
            outcome.getOrThrow()
        }
    }

    private fun markFailure(cause: RuntimeException): Nothing {
        updateLifecycleState(LifecycleState.Failed(cause))
        throw cause
    }

    private suspend fun <T> pipelinedSingleRead(
        propertyName: Name,
        operation: String,
        terminal: suspend () -> OperationOutcome<T>,
    ): OperationOutcome<T> {
        try {
            val descriptor = delegate.propertySpec(propertyName)?.descriptor
                ?: return unknownProperty(propertyName, operation)
            val opSpec = operationSpec(OperationKinds.Read)
            val context = OperationContext(OperationKinds.Read, propertyName, descriptor, name)
            val policy = operationPolicy(descriptor, opSpec)
            val tracker = delegate as? OperationTracker
            val plan = OperationPlan(context, policy) {
                terminal().eraseOutcome()
            }
            return trackedOperation(tracker) {
                readExecutor(plan, Unit).castOutcome()
            }
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    private suspend fun <T> pipelinedBatchRead(
        properties: Collection<Name>,
        operationName: Name,
        terminal: suspend (Collection<Name>) -> Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> =
        pipelinedBatch(
            kind = OperationKinds.Read,
            operationName = operationName,
            names = properties,
            resolveDescriptor = { propertyName -> delegate.propertySpec(propertyName)?.descriptor },
            missing = { propertyName -> unknownProperty(propertyName, "read") },
            terminal = terminal,
        )

    private suspend fun pipelinedBatchWrite(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        pipelinedBatch(
            kind = OperationKinds.Write,
            operationName = OperationNames.BatchWrite,
            names = values.keys,
            resolveDescriptor = { propertyName ->
                (delegate.propertySpec(propertyName) as? MutableDevicePropertyContract<*>)?.descriptor
            },
            missing = { propertyName -> unknownProperty(propertyName, "write") },
            terminal = { names ->
                val selected = names.toSet()
                delegate.writeBatchOutcome(values.filterKeys { it in selected })
            },
        )

    private suspend fun <T> pipelinedBatch(
        kind: OperationKind,
        operationName: Name,
        names: Collection<Name>,
        resolveDescriptor: (Name) -> PropertyDescriptor?,
        missing: (Name) -> OperationOutcome.Fail,
        terminal: suspend (Collection<Name>) -> Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> {
        if (names.isEmpty()) return emptyMap()
        val descriptors = LinkedHashMap<Name, PropertyDescriptor>()
        val results = LinkedHashMap<Name, OperationOutcome<T>>()
        for (propertyName in names) {
            val descriptor = resolveDescriptor(propertyName)
            if (descriptor == null) {
                results[propertyName] = missing(propertyName)
            } else {
                descriptors[propertyName] = descriptor
            }
        }
        if (descriptors.isEmpty()) return results

        val opSpec = operationSpec(kind)
        for ((propertyName, descriptor) in descriptors) {
            val gateContext = OperationContext(kind, propertyName, descriptor, name)
            for (gate in opSpec.gates) {
                val gateResult = gate.check(gateContext)
                if (gateResult is OperationOutcome.Fail) {
                    results[propertyName] = gateResult
                }
            }
        }
        val eligible = descriptors.filterKeys { it !in results }
        if (eligible.isEmpty()) return results

        val policy = batchPolicy(eligible.values, opSpec)
        val plan = OperationPlan(
            context = OperationContext(kind, operationName, batchDescriptor(operationName), name),
            policy = policy,
        ) {
            OperationOutcome.Ok(terminal(eligible.keys)).eraseOutcome()
        }
        val tracker = delegate as? OperationTracker
        val outcome: OperationOutcome<Map<Name, OperationOutcome<T>>> = trackedOperation(tracker) {
            when (kind) {
                OperationKinds.Write -> writeExecutor(plan, Unit).castOutcome()
                else -> readExecutor(plan, Unit).castOutcome()
            }
        }
        when (outcome) {
            is OperationOutcome.Fail -> eligible.keys.forEach { propertyName -> results[propertyName] = outcome }
            is OperationOutcome.Ok -> {
                for (propertyName in eligible.keys) {
                    results[propertyName] = outcome.value[propertyName]
                        ?: OperationOutcome.Fail(
                            GenericOperationFault(
                                message = "Batch operation '$operationName' did not return property '$propertyName'.",
                            ),
                        )
                }
            }
        }
        return results
    }

    private fun batchPolicy(
        descriptors: Collection<PropertyDescriptor>,
        opSpec: OperationPipelineSpec,
    ): OperationPolicy {
        val timeouts = descriptors.mapNotNull { it.timeout ?: opSpec.defaultTimeout }
        val retries = descriptors.map { it.retryPolicy ?: opSpec.defaultRetry }.distinct()
        return OperationPolicy(
            timeout = timeouts.minOrNull(),
            retry = retries.singleOrNull(),
            locks = descriptors.flatMap { it.requiredLocks },
        )
    }
}

private fun batchDescriptor(name: Name): PropertyDescriptor =
    PropertyDescriptor(
        name = name,
        kind = PropertyKind.LOGICAL,
        valueTypeId = TypeIds.META,
    )

@Suppress("UNCHECKED_CAST")
private fun <C : Capability<*>> Collection<Capability<*>>.firstCapability(key: CapabilityKey<C>): C? =
    firstOrNull { it.key == key || it.key.id == key.id } as? C

@Suppress("UNCHECKED_CAST")
private fun <T> OperationOutcome<Any?>.castOutcome(): OperationOutcome<T> =
    this as OperationOutcome<T>

@Suppress("UNCHECKED_CAST")
private fun <T> OperationOutcome<T>.eraseOutcome(): OperationOutcome<Any?> =
    this as OperationOutcome<Any?>

@Suppress("UNCHECKED_CAST")
private fun <T> Any?.castPayload(): T = this as T

@Suppress("UNCHECKED_CAST")
private fun <T> TypedReader<T>.outcomeReaderOrNull(): OutcomeTypedReader<T>? =
    this as? OutcomeTypedReader<T>

@Suppress("UNCHECKED_CAST")
private fun <T> TypedWriter<T>.outcomeWriterOrNull(): OutcomeTypedWriter<T>? =
    this as? OutcomeTypedWriter<T>

@Suppress("UNCHECKED_CAST")
private fun <I, O> TypedAction<I, O>.outcomeActionOrNull(): OutcomeTypedAction<I, O>? =
    this as? OutcomeTypedAction<I, O>

@Suppress("UNCHECKED_CAST")
private fun DevicePropertyContract<*>.asAnyPropertyContract(): DevicePropertyContract<Any?> =
    this as DevicePropertyContract<Any?>

@Suppress("UNCHECKED_CAST")
private fun MutableDevicePropertyContract<*>.asAnyMutablePropertyContract(): MutableDevicePropertyContract<Any?> =
    this as MutableDevicePropertyContract<Any?>

@Suppress("UNCHECKED_CAST")
private fun DeviceActionContract<*, *>.asAnyActionContract(): DeviceActionContract<Any?, Any?> =
    this as DeviceActionContract<Any?, Any?>

@Suppress("UNCHECKED_CAST")
private fun <I, O> (suspend (Any?) -> Any?).castActionExecutor(): suspend (I) -> O? =
    this as suspend (I) -> O?

private suspend fun <T> trackedOperation(
    tracker: OperationTracker?,
    block: suspend () -> OperationOutcome<T>,
): OperationOutcome<T> =
    if (tracker == null) {
        block()
    } else {
        tracker.enterOperation()
        try {
            block()
        } finally {
            tracker.exitOperation()
        }
    }

private data class CachedReader(
    val descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    val converter: MetaConverter<*>,
    val reader: TypedReader<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> readerFor(spec: DevicePropertyContract<T>): TypedReader<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return reader as TypedReader<T>
    }
}

private interface OutcomeTypedReader<T> : TypedReader<T> {
    suspend fun readOutcome(): OperationOutcome<T>
}

private interface OutcomeTypedWriter<T> : TypedWriter<T> {
    suspend fun writeOutcome(value: T): OperationOutcome<Unit>
}

private interface OutcomeTypedAction<I, O> : TypedAction<I, O> {
    suspend fun executeOutcome(input: I): OperationOutcome<O?>
}

private data class CachedWriter(
    val descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    val converter: MetaConverter<*>,
    val writer: TypedWriter<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> writerFor(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return writer as TypedWriter<T>
    }
}

private data class CachedAction(
    val descriptor: space.kscience.krig.api.descriptors.ActionDescriptor,
    val inputConverter: MetaConverter<*>,
    val outputConverter: MetaConverter<*>,
    val executor: suspend (Any?) -> Any?,
) {
    fun executorFor(spec: DeviceActionContract<Any?, Any?>): suspend (Any?) -> Any? {
        check(descriptor == spec.descriptor && inputConverter === spec.inputConverter && outputConverter === spec.outputConverter) {
            "Action '${spec.name}' was requested with a different descriptor or converter instance."
        }
        return executor
    }
}

private fun CachedReader.requireCompatible(
    descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

private fun CachedWriter.requireCompatible(
    descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

private fun unknownProperty(propertyName: Name, operation: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownProperty,
            message = "Cannot $operation property '$propertyName': no DevicePropertyContract is registered.",
        ),
    )

private fun unknownAction(actionName: Name): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownAction,
            message = "Cannot execute action '$actionName': no DeviceActionContract is registered.",
        ),
    )

private fun <T> decodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: Meta,
    kind: String,
    name: Name,
): OperationOutcome<T> {
    try {
        converter.readOrNull(value)?.let { return OperationOutcome.Ok(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OperationFaultException) {
        return OperationOutcome.Fail(e.fault)
    } catch (e: Exception) {
        return invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
    }
    return invalidControlPlanePayload(kind, name, "Payload does not match the registered converter.", null)
}

private fun <T> encodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: T,
    kind: String,
    name: Name,
): OperationOutcome<Meta> = try {
    OperationOutcome.Ok(converter.convert(value))
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    OperationOutcome.Fail(e.fault)
} catch (e: Exception) {
    invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
}

private fun invalidControlPlanePayload(
    kind: String,
    name: Name,
    message: String,
    cause: Throwable?,
): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            details = faultDetails(message = message, kind = kind, name = name, cause = cause),
        ),
    )
