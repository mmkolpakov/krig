@file:Suppress("RemoveRedundantQualifierName")
@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.contracts.CapabilityRegistry
import space.kscience.krig.core.contracts.CapabilityToggles
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.DynamicDiscoveryPolicy
import space.kscience.krig.core.contracts.EmptyDeviceNodeChildren
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.operations.ResourceLockRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed-primary pipeline decorator. Wraps a [delegate] [Device] so every typed, `Meta`, and batch
 * call crosses the same operation QoS pipeline.
 *
 * The decorator is a thin adapter: QoS compilation and execution live in [PipelineEngine],
 * compile-once memoization in [CompiledOperationCache]. Cross-cutting concerns (gates, observers,
 * timeout, retry, resource locks) are configured via [OperationPipelineSpec] — adding a new concern
 * is an additive field, not a new chain interface.
 *
 * Constructor is `@InternalKrigApi`; assemble through [wrapWithPipeline] which wires per-device
 * defaults (lifecycle, RBAC, audit, latency-budget).
 */
public class PipelineDevice @InternalKrigApi constructor(
    @property:InternalKrigApi public val delegate: Device,
    operationSpecs: Map<OperationKind, OperationPipelineSpec> = emptyMap(),
    readDecorators: List<ReadDecorator> = emptyList(),
    batchReadDecorators: List<BatchReadDecorator> = emptyList(),
    registry: ResourceLockRegistry = ResourceLockRegistry(),
    capabilities: Collection<Capability<*>> = emptyList(),
) : Device by delegate, LifecycleStateHolder, CapabilityHost, DeviceNode, DynamicDescriptorOverlay {

    private val engine = PipelineEngine(
        delegate = delegate,
        hostName = name,
        operationSpecs = operationSpecs,
        readDecorators = readDecorators,
        batchReadDecorators = batchReadDecorators,
        lockRegistry = registry,
        timeSource = timeSource,
    )
    private val cache = CompiledOperationCache()

    // Single capability store: assembly-time snapshot capabilities are folded into the same
    // registry as runtime-registered ones, so lookup/detach have one source plus the delegate.
    private val capabilityRegistry = CapabilityRegistry()

    init {
        capabilities.forEach(capabilityRegistry::registerCapability)
    }

    // --- Topology pass-through ---

    override val device: Device get() = this

    override val children: Map<Name, DeviceNode>
        get() = (delegate as? DeviceNode)?.children.orEmpty()

    override val childrenFlow: StateFlow<Map<Name, DeviceNode>>
        get() = (delegate as? DeviceNode)?.childrenFlow ?: EmptyDeviceNodeChildren

    override val dynamicDiscoveryPolicy: DynamicDiscoveryPolicy
        get() = (delegate as? DynamicDescriptorOverlay)?.dynamicDiscoveryPolicy ?: DynamicDiscoveryPolicy.Strict

    override val discoveredPropertyDescriptors: Map<Name, PropertyDescriptor>
        get() = (delegate as? DynamicDescriptorOverlay)?.discoveredPropertyDescriptors.orEmpty()

    // --- Capability host: own registry merged with the delegate ---

    /** Capability background scope: child of the (delegated) [deviceScope], supervised. */
    override val capabilityScope: CoroutineScope =
        CoroutineScope(deviceScope.coroutineContext + SupervisorJob(deviceScope.coroutineContext[Job]))

    override val capabilityToggles: CapabilityToggles =
        (delegate as? CapabilityHost)?.capabilityToggles ?: CapabilityToggles()

    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        capabilityRegistry.capability(key)
            ?: delegate.capability(key)

    override val installedCapabilities: Collection<Capability<*>>
        get() = (
                capabilityRegistry.installedCapabilities +
                        (delegate as? CapabilityHost)?.installedCapabilities.orEmpty()
                ).distinctBy { it.key.id }

    override fun registerCapability(capability: Capability<*>) {
        capabilityRegistry.registerCapability(capability)
    }

    @InternalKrigApi
    override fun <C : Capability<*>> getOrRegisterCapability(
        key: CapabilityKey<C>,
        factory: () -> C,
    ): C =
        capabilityRegistry.capability(key)
            ?: (delegate as? CapabilityHost)?.capability(key)
            ?: capabilityRegistry.getOrRegisterCapability(key, factory)

    // --- Lifecycle delegation + centralised failure promotion ---

    override val lifecycleStateFlow: StateFlow<LifecycleState>?
        get() = (delegate as? LifecycleStateHolder)?.lifecycleStateFlow

    override fun updateLifecycleState(state: LifecycleState) {
        (delegate as? LifecycleStateHolder)?.updateLifecycleState(state)
    }

    /**
     * Best-effort, non-suspending close. [CoroutineStart.UNDISPATCHED] starts detach immediately,
     * and [CapabilityRegistry.detachOnce] bounds each capability cleanup, so `delegate.close()`
     * cannot abort a capability `onDetach` at its first suspension point. Prefer [shutdown] for
     * orderly release.
     */
    override fun close() {
        deviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            capabilityRegistry.detachOnce(this@PipelineDevice)
        }
        delegate.close()
    }

    override suspend fun shutdown() {
        capabilityRegistry.detachOnce(this)
        delegate.shutdown()
    }

    private fun markFailure(cause: RuntimeException): Nothing {
        updateLifecycleState(LifecycleState.Failed(cause))
        throw cause
    }

    /** Single failure policy for the Meta surface: faults map to `Fail`, hard errors promote to `Failed`. */
    private suspend fun <T> guardingLifecycle(
        block: suspend () -> OperationOutcome<T>,
    ): OperationOutcome<T> =
        try {
            block()
        } catch (e: OperationFaultException) {
            OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }

    /**
     * Batch failure policy, consistent with [guardingLifecycle]. Per-property faults stay in the
     * result map as `Fail` (the engine fans them out — errors-as-values for every member). An
     * operation fault that escapes the terminal maps to a uniform `Fail` for every requested name,
     * while a hard [RuntimeException] promotes the device to [LifecycleState.Failed] and rethrows —
     * so a systemic bug in the batch terminal is not masked as a set of per-property failures.
     */
    private suspend fun <T> guardingBatchLifecycle(
        names: Collection<Name>,
        block: suspend () -> Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> =
        try {
            block()
        } catch (e: OperationFaultException) {
            names.associateWith { OperationOutcome.Fail(e.fault) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }

    // --- Typed contract: compile pipeline ONCE per reader/writer/action ---

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        cache.reader(spec.name) {
            CachedReader(spec.descriptor, spec.converter, engine.compileReader(spec))
        }.readerFor(spec)

    override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T> =
        TypedObservedReader { readObservedOutcome(spec).getOrThrow() }

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        cache.writer(spec.name) {
            CachedWriter(spec.descriptor, spec.converter, engine.compileWriter(spec))
        }.writerFor(spec)

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        delegate.sampler(spec)

    override suspend fun <T> readOutcome(spec: DevicePropertyContract<T>): OperationOutcome<T> {
        val compiled = reader(spec)
        return compiled.outcomeReaderOrNull()?.readOutcome()
            ?: catchingOperationOutcome { compiled.read() }
    }

    override suspend fun <T> readObservedOutcome(
        spec: DevicePropertyContract<T>,
    ): OperationOutcome<ObservedValue<T?>> =
        guardingLifecycle {
            engine.pipelinedSingleRead(spec.name, "read observed") {
                delegate.readObservedOutcome(spec)
            }
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

    private fun actionExecutor(spec: DeviceActionContract<Any?, Any?>): suspend (Any?) -> Any? =
        cache.action(spec.name) {
            CachedAction(
                descriptor = spec.descriptor,
                inputConverter = spec.inputConverter,
                outputConverter = spec.outputConverter,
                executor = engine.compileAction(spec),
            )
        }.executorFor(spec)

    // --- Control-plane Meta boundary: route through the operation pipeline when spec is known ---
    //
    // When the delegate registers a contract for the requested name (typically through
    // DeviceManifest), the serialization-facing Meta API crosses into the typed executor so
    // timeout / retry / locks / observers all fire. Unknown names fail fast.

    override suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        guardingLifecycle {
            val spec = delegate.propertySpec(propertyName)
                ?: return@guardingLifecycle unknownProperty(propertyName, "read")
            val typedSpec = spec.asAnyPropertyContract()
            when (val typed = catchingOperationOutcome { reader(typedSpec).read() }) {
                is OperationOutcome.Fail -> typed
                is OperationOutcome.Ok -> encodeControlPlaneMeta(typedSpec.converter, typed.value, "property", propertyName)
            }
        }

    override suspend fun readObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        guardingLifecycle {
            engine.pipelinedSingleRead(propertyName, "read observed") {
                delegate.readObservedOutcome(propertyName)
            }
        }

    override suspend fun readBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        guardingLifecycle {
            engine.pipelinedSingleRead(propertyName, "read binary") {
                delegate.readBinaryOutcome(propertyName)
            }
        }

    // Batch operations use [guardingBatchLifecycle] instead of the single-op [guardingLifecycle]:
    // the engine maps per-property failures into the result map (errors-as-values for every member),
    // so a member fault must not promote the whole device to Failed; but a hard RuntimeException that
    // escapes the terminal still promotes the device to Failed, consistent with the single-op path.
    override suspend fun readBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        guardingBatchLifecycle(properties) {
            val terminal = engine.decorateObservedBatchRead { names -> delegate.readBatchOutcome(names) }
            engine.pipelinedBatchRead(properties, OperationNames.BatchRead, terminal)
        }

    override suspend fun readBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> =
        guardingBatchLifecycle(properties) {
            engine.pipelinedBatchRead(properties, OperationNames.BatchReadBinary) { names ->
                delegate.readBatchBinaryOutcome(names)
            }
        }

    override suspend fun writeBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        guardingBatchLifecycle(values.keys) {
            engine.pipelinedBatchWrite(values)
        }

    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        guardingLifecycle {
            val spec = delegate.propertySpec(propertyName) as? MutableDevicePropertyContract<*>
                ?: return@guardingLifecycle unknownProperty(propertyName, "write")
            val mutable = spec.asAnyMutablePropertyContract()
            when (val decoded = decodeControlPlaneMeta(mutable.converter, value, "property", propertyName)) {
                is OperationOutcome.Fail -> decoded
                is OperationOutcome.Ok -> catchingOperationOutcome { writer(mutable).write(decoded.value) }
            }
        }

    override suspend fun executeOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        guardingLifecycle {
            val spec = delegate.actionSpec(actionName)
                ?: return@guardingLifecycle unknownAction(actionName)
            executeControlPlaneAction(spec.asAnyActionContract(), actionName, argument)
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
}
