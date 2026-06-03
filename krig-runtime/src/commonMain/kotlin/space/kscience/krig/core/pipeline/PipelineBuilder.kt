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
import space.kscience.attributes.AttributesBuilder
import space.kscience.attributes.isEmpty
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
    )

    private val hookRegistry: HookRegistry = HookRegistry.buffered()

    override fun <H : Any> on(hook: Hook<H>, handler: H): Unit = hookRegistry.on(hook, handler)
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

    public val capabilities: AttributesBuilder<Capability<*>> = AttributesBuilder()

    /** Optional connection-state supplier. When non-null, the assembler installs connection gates. */
    @InternalKrigApi
    public var connectionStateProvider: (() -> ConnectionState)? = null

    public fun gates(kind: OperationKind): List<OperationGate> = gates.load()[kind].orEmpty()
    public fun observers(kind: OperationKind): List<OperationObserver> = observers.load()[kind].orEmpty()
    public val readDecorators: List<ReadDecorator> get() = readDecoratorsRef.load()

    public fun gate(kind: OperationKind, gate: OperationGate) {
        gates.update { it.append(kind, gate) }
    }

    public fun observe(kind: OperationKind, observer: OperationObserver) {
        observers.update { it.append(kind, observer) }
    }

    public fun decorateRead(decorator: ReadDecorator) {
        readDecoratorsRef.update { it.add(decorator) }
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

    @InternalKrigApi
    public fun prependGates(kind: OperationKind, values: List<OperationGate>) {
        if (values.isNotEmpty()) gates.update { it.prepend(kind, values) }
    }

    @InternalKrigApi
    public fun prependObservers(kind: OperationKind, values: List<OperationObserver>) {
        if (values.isNotEmpty()) observers.update { it.prepend(kind, values) }
    }

    public fun <C : Capability<*>> registerCapability(key: CapabilityKey<C>, capability: C) {
        capabilities.put(key, capability)
    }

    @Suppress("UNCHECKED_CAST")
    public fun registerCapability(capability: Capability<*>) {
        registerCapability(capability.key as CapabilityKey<Capability<*>>, capability)
    }

    @OptIn(InternalKrigApi::class)
    public fun useConnectionState(supplier: () -> ConnectionState) {
        connectionStateProvider = supplier
    }

    @OptIn(InternalKrigApi::class)
    public fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        capabilities.attributes()[key]

    @OptIn(InternalKrigApi::class)
    override fun isEmpty(): Boolean =
        gates.load().values.all { it.isEmpty() } &&
            observers.load().values.all { it.isEmpty() } &&
            readDecorators.isEmpty() &&
            policies.load().isEmpty() &&
            capabilities.attributes().isEmpty() &&
            connectionStateProvider == null &&
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
