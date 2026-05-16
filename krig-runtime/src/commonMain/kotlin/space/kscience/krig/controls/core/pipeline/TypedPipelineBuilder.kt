@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    InternalKrigApi::class,
)

package space.kscience.krig.core.pipeline

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import space.kscience.attributes.AttributesBuilder
import space.kscience.attributes.isEmpty
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.DeviceCapability
import space.kscience.krig.core.hook.Hook
import space.kscience.krig.core.hook.HookRegistration
import space.kscience.krig.core.hook.HookRegistry

/**
 * Mutable builder for [ReadPipelineSpec] / [WritePipelineSpec] / [ActionPipelineSpec],
 * the operation-pipeline assembly point used by device features and the DSL.
 *
 * Each plane (read / write / action) carries its own gates, observers and resilience
 * defaults; all collections are atomic [PersistentList]s so concurrent DeviceFeatureSpec
 * installation is CAS-safe. Capabilities and hooks are aggregated alongside.
 *
 * ```kotlin
 * override fun install(config: Config, pipeline: TypedPipelineBuilder) {
 *     pipeline.registerCapability(CachingCapability.Key, cap)
 *     pipeline.addReadObserver(CachedReadObserver(cap, config.ttl))
 * }
 * ```
 */
public class TypedPipelineBuilder : HookRegistry {

    private val hookRegistry: HookRegistry = HookRegistry.buffered()

    override fun <H : Any> on(hook: Hook<H>, handler: H): Unit = hookRegistry.on(hook, handler)
    override fun <H : Any> register(hook: Hook<H>, handler: H): HookRegistration =
        hookRegistry.register(hook, handler)
    override fun <H : Any> off(hook: Hook<H>, handler: H): Unit = hookRegistry.off(hook, handler)
    override fun <H : Any> handlersOf(hook: Hook<H>): List<H> = hookRegistry.handlersOf(hook)

    private val _readGates: AtomicReference<PersistentList<ReadGate>> = AtomicReference(persistentListOf())
    private val _readDecorators: AtomicReference<PersistentList<ReadDecorator>> = AtomicReference(persistentListOf())
    private val _readObservers: AtomicReference<PersistentList<ReadObserver>> = AtomicReference(persistentListOf())
    private val _writeGates: AtomicReference<PersistentList<WriteGate>> = AtomicReference(persistentListOf())
    private val _writeObservers: AtomicReference<PersistentList<WriteObserver>> = AtomicReference(persistentListOf())
    private val _actionGates: AtomicReference<PersistentList<ActionGate>> = AtomicReference(persistentListOf())
    private val _actionObservers: AtomicReference<PersistentList<ActionObserver>> = AtomicReference(persistentListOf())
    private val _samplingObservers: AtomicReference<PersistentList<SamplingObserver>> = AtomicReference(persistentListOf())

    public val readGates: List<ReadGate> get() = _readGates.load()
    public val readDecorators: List<ReadDecorator> get() = _readDecorators.load()
    public val readObservers: List<ReadObserver> get() = _readObservers.load()
    public val writeGates: List<WriteGate> get() = _writeGates.load()
    public val writeObservers: List<WriteObserver> get() = _writeObservers.load()
    public val actionGates: List<ActionGate> get() = _actionGates.load()
    public val actionObservers: List<ActionObserver> get() = _actionObservers.load()

    /** After-sample observers — invoked on every TypedSampler flow element. */
    public val samplingObservers: List<SamplingObserver> get() = _samplingObservers.load()

    public var readDefaultTimeout: Duration? = null
    public var readDefaultRetry: RetryPolicy? = null
    public var readDefaultLatencyBudget: Duration? = null

    public var writeDefaultTimeout: Duration? = null
    public var writeDefaultRetry: RetryPolicy? = null
    public var writeDefaultLatencyBudget: Duration? = null

    public var actionDefaultTimeout: Duration? = null
    public var actionDefaultRetry: RetryPolicy? = null
    public var actionDefaultLatencyBudget: Duration? = null

    public val capabilities: AttributesBuilder<DeviceCapability<*>> = AttributesBuilder()

    /** Optional connection-state supplier. When non-null, the assembler installs connection gates. */
    @InternalKrigApi
    public var connectionStateProvider: (() -> ConnectionState)? = null

    public fun addReadGate(gate: ReadGate) { _readGates.update { it.add(gate) } }
    public fun addReadDecorator(decorator: ReadDecorator) { _readDecorators.update { it.add(decorator) } }
    public fun addReadObserver(observer: ReadObserver) { _readObservers.update { it.add(observer) } }
    public fun addWriteGate(gate: WriteGate) { _writeGates.update { it.add(gate) } }
    public fun addWriteObserver(observer: WriteObserver) { _writeObservers.update { it.add(observer) } }
    public fun addActionGate(gate: ActionGate) { _actionGates.update { it.add(gate) } }
    public fun addActionObserver(observer: ActionObserver) { _actionObservers.update { it.add(observer) } }
    public fun addSamplingObserver(observer: SamplingObserver) { _samplingObservers.update { it.add(observer) } }

    public fun <C : DeviceCapability<*>> registerCapability(key: CapabilityKey<C, *>, capability: C) {
        capabilities.put(key, capability)
    }

    @OptIn(InternalKrigApi::class)
    public fun useConnectionState(supplier: () -> ConnectionState) {
        connectionStateProvider = supplier
    }

    @OptIn(InternalKrigApi::class)
    public fun <C : DeviceCapability<*>> capability(key: CapabilityKey<C, *>): C? =
        capabilities.attributes()[key]

    @OptIn(InternalKrigApi::class)
    override fun isEmpty(): Boolean =
        readGates.isEmpty() && readDecorators.isEmpty() && readObservers.isEmpty() &&
            writeGates.isEmpty() && writeObservers.isEmpty() &&
            actionGates.isEmpty() && actionObservers.isEmpty() &&
            samplingObservers.isEmpty() &&
            capabilities.attributes().isEmpty() &&
            connectionStateProvider == null &&
            hookRegistry.isEmpty()

    @InternalKrigApi
    public fun toReadSpec(): ReadPipelineSpec = ReadPipelineSpec(
        gates = readGates,
        decorators = readDecorators,
        observers = readObservers,
        defaultTimeout = readDefaultTimeout,
        defaultRetry = readDefaultRetry,
        defaultLatencyBudget = readDefaultLatencyBudget,
    )

    @InternalKrigApi
    public fun toWriteSpec(): WritePipelineSpec = WritePipelineSpec(
        gates = writeGates,
        observers = writeObservers,
        defaultTimeout = writeDefaultTimeout,
        defaultRetry = writeDefaultRetry,
        defaultLatencyBudget = writeDefaultLatencyBudget,
    )

    @InternalKrigApi
    public fun toActionSpec(): ActionPipelineSpec = ActionPipelineSpec(
        gates = actionGates,
        observers = actionObservers,
        defaultTimeout = actionDefaultTimeout,
        defaultRetry = actionDefaultRetry,
        defaultLatencyBudget = actionDefaultLatencyBudget,
    )

    @InternalKrigApi
    public fun toSamplingSpec(): SamplingPipelineSpec = SamplingPipelineSpec(
        observers = samplingObservers,
    )
}
