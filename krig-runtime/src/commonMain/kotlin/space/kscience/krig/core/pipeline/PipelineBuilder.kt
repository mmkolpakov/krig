@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    InternalKrigApi::class,
)

package space.kscience.krig.core.pipeline

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.hook.Hook
import space.kscience.krig.core.hook.HookRegistration
import space.kscience.krig.core.hook.HookRegistry

/**
 * Operation-pipeline assembly point. Device read/write/action helpers are thin wrappers
 * over open [OperationKind]s; integrations can add their own kind names.
 */
public class PipelineBuilder : HookRegistry {

    private data class PipelineKindDefaults(
        val timeout: Duration? = null,
        val retry: RetryPolicy? = null,
        val latencyBudget: Duration? = null,
        val batchExecutionMode: BatchExecutionMode = BatchExecutionMode.Sequential,
    )

    private val hookRegistry: HookRegistry = HookRegistry.buffered()

    @IgnorableReturnValue
    override fun <H : Any> register(hook: Hook<H>, handler: H): HookRegistration =
        hookRegistry.register(hook, handler)
    override fun <H : Any> off(hook: Hook<H>, handler: H): Unit = hookRegistry.off(hook, handler)
    override fun <H : Any> handlersOf(hook: Hook<H>): List<H> = hookRegistry.handlersOf(hook)

    private val gates: AtomicReference<PersistentMap<OperationKind, PersistentList<OperationGate>>> =
        AtomicReference(persistentMapOf())
    private val observers: AtomicReference<PersistentMap<OperationKind, PersistentList<OperationObserver>>> =
        AtomicReference(persistentMapOf())
    private val policies: AtomicReference<PersistentMap<OperationKind, PipelineKindDefaults>> =
        AtomicReference(persistentMapOf())
    private val readDecoratorsRef: AtomicReference<PersistentList<ReadDecorator>> =
        AtomicReference(persistentListOf())
    private val batchReadDecoratorsRef: AtomicReference<PersistentList<BatchReadDecorator>> =
        AtomicReference(persistentListOf())
    private val capabilitiesRef: AtomicReference<PersistentMap<Name, Capability<*>>> =
        AtomicReference(persistentMapOf())

    /** Runtime capability instances to attach during pipeline assembly. */
    public val capabilities: Collection<Capability<*>> get() = capabilitiesRef.load().values

    /** Optional connection-state supplier. When non-null, the assembler installs connection gates. */
    @InternalKrigApi
    public var connectionStateProvider: (() -> ConnectionState)? = null

    private val suppressDescriptorQosRef: AtomicReference<Boolean> = AtomicReference(false)

    /**
     * Ignore descriptor-level operational QoS (manifest timeout/retry) for every kind, leaving
     * only kind-level defaults. Used by digital-twin profiles: hardware deadlines authored in the
     * manifest have no meaning for an in-process model.
     */
    public fun suppressDescriptorQos(suppress: Boolean = true) {
        suppressDescriptorQosRef.store(suppress)
    }

    public fun gates(kind: OperationKind): List<OperationGate> = gates.load()[kind].orEmpty()
    public fun observers(kind: OperationKind): List<OperationObserver> = observers.load()[kind].orEmpty()
    public val readDecorators: List<ReadDecorator> get() = readDecoratorsRef.load()
    public val batchReadDecorators: List<BatchReadDecorator> get() = batchReadDecoratorsRef.load()

    public fun gate(kind: OperationKind, gate: OperationGate) {
        gates.update { it.append(kind, gate) }
    }

    public fun observe(kind: OperationKind, observer: OperationObserver) {
        observers.update { it.append(kind, observer) }
    }

    public fun decorateRead(decorator: ReadDecorator) {
        readDecoratorsRef.update { it.add(decorator) }
    }

    public fun decorateBatchRead(decorator: BatchReadDecorator) {
        batchReadDecoratorsRef.update { it.add(decorator) }
    }

    public fun timeout(kind: OperationKind, timeout: Duration?) {
        updatePolicy(kind) { it.copy(timeout = timeout) }
    }

    public fun retry(kind: OperationKind, retry: RetryPolicy?) {
        updatePolicy(kind) { it.copy(retry = retry) }
    }

    public fun latencyBudget(kind: OperationKind, budget: Duration?) {
        updatePolicy(kind) { it.copy(latencyBudget = budget) }
    }

    /**
     * Declares how this device's backend services batches of [kind]. Default is
     * [BatchExecutionMode.Sequential]; a driver that reads/writes a batch as one transaction
     * (OPC UA read, Modbus block) sets [BatchExecutionMode.Coalescing] so whole-batch timeouts
     * use the maximum member budget rather than their sum.
     */
    public fun batchExecutionMode(kind: OperationKind, mode: BatchExecutionMode) {
        updatePolicy(kind) { it.copy(batchExecutionMode = mode) }
    }

    @InternalKrigApi
    public fun prependGates(kind: OperationKind, values: List<OperationGate>) {
        if (values.isNotEmpty()) gates.update { it.prepend(kind, values) }
    }

    @InternalKrigApi
    public fun prependObservers(kind: OperationKind, values: List<OperationObserver>) {
        if (values.isNotEmpty()) observers.update { it.prepend(kind, values) }
    }

    public fun <C : Capability<*>> registerCapability(key: CapabilityKey<C>, capability: C) {
        capabilitiesRef.update { it.put(key.id, capability) }
    }

    public fun registerCapability(capability: Capability<*>) {
        capabilitiesRef.update { it.put(capability.key.id, capability) }
    }

    @OptIn(InternalKrigApi::class)
    public fun useConnectionState(supplier: () -> ConnectionState) {
        connectionStateProvider = supplier
    }

    @OptIn(InternalKrigApi::class)
    @Suppress("UNCHECKED_CAST")
    public fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        capabilitiesRef.load()[key.id] as? C

    @OptIn(InternalKrigApi::class)
    override fun isEmpty(): Boolean =
        gates.load().values.all { it.isEmpty() } &&
            observers.load().values.all { it.isEmpty() } &&
            readDecorators.isEmpty() &&
            batchReadDecorators.isEmpty() &&
            policies.load().isEmpty() &&
            capabilitiesRef.load().isEmpty() &&
            connectionStateProvider == null &&
            !suppressDescriptorQosRef.load() &&
            hookRegistry.isEmpty()

    @InternalKrigApi
    public fun operationSpec(kind: OperationKind): OperationPipelineSpec {
        val policy = policies.load()[kind]
        return OperationPipelineSpec(
            gates = gates(kind),
            observers = observers(kind),
            defaultTimeout = policy?.timeout,
            defaultRetry = policy?.retry,
            defaultLatencyBudget = policy?.latencyBudget,
            suppressDescriptorQos = suppressDescriptorQosRef.load(),
            batchExecutionMode = policy?.batchExecutionMode ?: BatchExecutionMode.Sequential,
        )
    }

    private fun updatePolicy(kind: OperationKind, transform: (PipelineKindDefaults) -> PipelineKindDefaults) {
        policies.update { current ->
            current.put(kind, transform(current[kind] ?: PipelineKindDefaults()))
        }
    }
}

private fun <T> PersistentMap<OperationKind, PersistentList<T>>.append(
    kind: OperationKind,
    value: T,
): PersistentMap<OperationKind, PersistentList<T>> =
    put(kind, get(kind).orEmptyPersistent().add(value))

private fun <T> PersistentMap<OperationKind, PersistentList<T>>.prepend(
    kind: OperationKind,
    values: List<T>,
): PersistentMap<OperationKind, PersistentList<T>> =
    put(kind, values.toPersistentList().addAll(get(kind).orEmptyPersistent()))

private fun <T> PersistentList<T>?.orEmptyPersistent(): PersistentList<T> =
    this ?: persistentListOf()
