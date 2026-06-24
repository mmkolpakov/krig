@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
)

package space.kscience.krig.core.pipeline

import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.timeout
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.OperationTracker
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.operations.ResourceLockRegistry

/**
 * The single site where operation QoS is applied for one [delegate] device.
 *
 * Resolves an [OperationPolicy] from each descriptor and the per-kind [OperationPipelineSpec],
 * compiles the shared read/write/action executors once, and builds typed readers/writers/actions
 * plus the batch fan-out. Returns [OperationOutcome] and never touches device lifecycle — failure
 * promotion is the adapter's concern.
 */
internal class PipelineEngine(
    private val delegate: Device,
    private val hostName: Name,
    private val operationSpecs: Map<OperationKind, OperationPipelineSpec>,
    private val readDecorators: List<ReadDecorator>,
    private val batchReadDecorators: List<BatchReadDecorator>,
    private val lockRegistry: ResourceLockRegistry,
    private val timeSource: TimeSource,
) {
    private val tracker: OperationTracker? = delegate as? OperationTracker

    // Shared executors serve the dynamic Meta and batch paths, where the terminal varies per call.
    // Typed readers/writers/actions instead compile a per-reader interceptor chain (see below).
    private val readExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Read)
    }
    private val writeExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Write)
    }

    private fun operationSpec(kind: OperationKind): OperationPipelineSpec =
        operationSpecs[kind] ?: OperationPipelineSpec.Empty

    private fun compileSharedExecutor(
        kind: OperationKind,
    ): suspend (OperationPlan, Any?, OperationTerminal) -> OperationOutcome<Any?> {
        val opSpec = operationSpec(kind)
        return compileOperationExecutor(
            gates = opSpec.gates,
            observers = opSpec.observers,
            registry = lockRegistry,
            timeSource = timeSource,
        )
    }

    /**
     * Built-in interceptor chain for one operation [kind]. A runtime profile may trim layers by key
     * (e.g. `without(BuiltinInterceptorKeys.Timeout)`); the default order is timeout → gates → retry →
     * locks, with each layer a passthrough when its policy field is unset.
     */
    private fun interceptorsFor(opSpec: OperationPipelineSpec): List<OperationInterceptor> =
        defaultOperationInterceptors(opSpec.gates, lockRegistry)

    private fun operationPolicy(
        descriptor: OperationDescriptor,
        opSpec: OperationPipelineSpec,
    ): OperationPolicy =
        if (opSpec.suppressDescriptorQos) {
            // Profile decision (e.g. PipelineProfile.InMemory): manifest QoS authored for real
            // hardware is meaningless here, only kind-level defaults apply.
            OperationPolicy(
                timeout = opSpec.defaultTimeout,
                retry = opSpec.defaultRetry,
                locks = descriptor.requiredLocks,
            )
        } else {
            OperationPolicy(
                timeout = descriptor.timeout ?: opSpec.defaultTimeout,
                retry = descriptor.retryPolicy ?: opSpec.defaultRetry,
                locks = descriptor.requiredLocks,
            )
        }

    fun <T> compileReader(spec: DevicePropertyContract<T>): TypedReader<T> {
        val raw = delegate.reader(spec)
        val opSpec = operationSpec(OperationKinds.Read)
        val context = OperationContext(OperationKinds.Read, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val decorated = readDecorators.fold(raw) { acc, dec -> dec.decorate(spec, acc) }
        val plan = OperationPlan(context, policy)
        val terminal: OperationTerminal = {
            catchingOperationOutcome { decorated.read() }
        }
        val chain = interceptorsFor(opSpec).compileChain(plan, opSpec.observers, timeSource, terminal)
        return object : OutcomeTypedReader<T> {
            override suspend fun readOutcome(): OperationOutcome<T> =
                trackedOperation(tracker) {
                    chain(Unit).castOutcome()
                }

            override suspend fun read(): T = readOutcome().getOrThrow()
        }
    }

    fun <T> compileWriter(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        val raw = delegate.writer(spec)
        val opSpec = operationSpec(OperationKinds.Write)
        val context = OperationContext(OperationKinds.Write, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val plan = OperationPlan(context, policy)
        val terminal: OperationTerminal = { value ->
            catchingOperationOutcome { raw.write(value.castPayload()) }
        }
        val chain = interceptorsFor(opSpec).compileChain(plan, opSpec.observers, timeSource, terminal)
        return object : OutcomeTypedWriter<T> {
            override suspend fun writeOutcome(value: T): OperationOutcome<Unit> =
                trackedOperation(tracker) {
                    chain(value).castOutcome()
                }

            override suspend fun write(value: T) {
                writeOutcome(value).getOrThrow()
            }
        }
    }

    fun <I, O> compileAction(spec: DeviceActionContract<I, O>): suspend (I) -> O? {
        val opSpec = operationSpec(OperationKinds.Action)
        val context = OperationContext(OperationKinds.Action, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val plan = OperationPlan(context, policy)
        val terminal: OperationTerminal = { input ->
            catchingOperationOutcome {
                val argMeta = if (input != null) spec.inputConverter.convert(input.castPayload()) else null
                val resultMeta = delegate.execute(spec.name, argMeta)
                if (resultMeta != null) spec.outputConverter.read(resultMeta) else null
            }
        }
        val chain = interceptorsFor(opSpec).compileChain(plan, opSpec.observers, timeSource, terminal)
        return { input ->
            val outcome = trackedOperation(tracker) { chain(input).castOutcome<O?>() }
            outcome.getOrThrow()
        }
    }

    /**
     * Lazily memoized read-plan headers (context + policy) for the dynamic Meta read path, keyed by
     * property [Name]. Descriptors are static for a device, so a header resolved once is reused: this
     * removes the per-call descriptor lookup plus the `OperationContext`/`OperationPolicy` allocations,
     * mirroring how the compiled typed readers cache their plan. A stored `null` memoizes "no such
     * property" so repeated unknown lookups stay allocation-free too.
     */
    private val singleReadPlanLock = SynchronizedObject()
    private val singleReadPlans = mutableMapOf<Name, OperationPlan?>()

    private fun singleReadPlanFor(propertyName: Name): OperationPlan? = synchronized(singleReadPlanLock) {
        if (singleReadPlans.containsKey(propertyName)) return@synchronized singleReadPlans[propertyName]
        val descriptor = delegate.propertySpec(propertyName)?.descriptor
        val plan = descriptor?.let {
            OperationPlan(
                OperationContext(OperationKinds.Read, propertyName, it, hostName),
                operationPolicy(it, operationSpec(OperationKinds.Read)),
            )
        }
        singleReadPlans[propertyName] = plan
        plan
    }

    suspend fun <T> pipelinedSingleRead(
        propertyName: Name,
        operation: String,
        terminal: suspend () -> OperationOutcome<T>,
    ): OperationOutcome<T> {
        val plan = singleReadPlanFor(propertyName)
            ?: return unknownProperty(propertyName, operation)
        return trackedOperation(tracker) {
            readExecutor(plan, Unit) { terminal().eraseOutcome() }.castOutcome()
        }
    }

    /**
     * Wraps a batch-read terminal with the configured [BatchReadDecorator]s (cache / mock / rate-limit
     * on the coalescing read plane), so batch acquisitions are decorated the way single reads are by
     * [ReadDecorator]. Folded outside-in: the first decorator is the outermost wrapper.
     */
    fun decorateObservedBatchRead(
        terminal: suspend (Collection<Name>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>,
    ): suspend (Collection<Name>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        batchReadDecorators.foldRight(terminal) { decorator, acc -> decorator.decorate(acc) }

    suspend fun <T> pipelinedBatchRead(
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

    suspend fun pipelinedBatchWrite(
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

        // Resolve identity once for the whole batch: per-property gates and the batch-level executor
        // share one [ResolvedPrincipalCache] instead of re-resolving per member (the N+1 resolve).
        return withContext(ResolvedPrincipalCache()) {
            batchUnderResolvedPrincipal(kind, operationName, names, descriptors, results, terminal)
        }
    }

    private suspend fun <T> batchUnderResolvedPrincipal(
        kind: OperationKind,
        operationName: Name,
        names: Collection<Name>,
        descriptors: LinkedHashMap<Name, PropertyDescriptor>,
        results: LinkedHashMap<Name, OperationOutcome<T>>,
        terminal: suspend (Collection<Name>) -> Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> {
        val opSpec = operationSpec(kind)
        for ((propertyName, descriptor) in descriptors) {
            val gateContext = OperationContext(kind, propertyName, descriptor, hostName)
            for (gate in opSpec.gates) {
                val gateResult = gate.check(gateContext)
                if (gateResult is OperationOutcome.Fail) {
                    results[propertyName] = gateResult
                }
            }
        }
        val eligible = descriptors.filterKeys { it !in results }
        if (eligible.isEmpty()) return orderedResults(names, results)

        // Partition eligible members by their effective RetryPolicy so heterogeneous policies are
        // each honored on their own sub-batch, instead of silently disabling retry for the whole
        // batch. Homogeneous batches (the common case) collapse to a single group → one terminal call.
        val groups = eligible.entries.groupBy { (_, descriptor) -> effectiveRetry(descriptor, opSpec) }
        val executor = when (kind) {
            OperationKinds.Write -> writeExecutor
            else -> readExecutor
        }
        for ((retry, entries) in groups) {
            val groupNames = entries.map { it.key }
            val policy = batchPolicy(entries.map { it.value }, opSpec, retry)
            val plan = OperationPlan(
                context = OperationContext(kind, operationName, batchDescriptor(operationName), hostName),
                policy = policy,
            )
            val batchTerminal: OperationTerminal = {
                OperationOutcome.Ok(terminal(groupNames)).eraseOutcome()
            }
            val outcome: OperationOutcome<Map<Name, OperationOutcome<T>>> = trackedOperation(tracker) {
                executor(plan, Unit, batchTerminal).castOutcome()
            }
            when (outcome) {
                is OperationOutcome.Fail -> groupNames.forEach { propertyName -> results[propertyName] = outcome }
                is OperationOutcome.Ok -> {
                    for (propertyName in groupNames) {
                        results[propertyName] = outcome.value[propertyName]
                            ?: OperationOutcome.Fail(
                                GenericOperationFault(
                                    message = "Batch operation '$operationName' did not return property '$propertyName'.",
                                ),
                            )
                    }
                }
            }
        }
        return orderedResults(names, results)
    }

    /** Re-projects accumulated [results] into the caller's [names] order (retry groups iterate freely). */
    private fun <T> orderedResults(
        names: Collection<Name>,
        results: Map<Name, OperationOutcome<T>>,
    ): Map<Name, OperationOutcome<T>> {
        val ordered = LinkedHashMap<Name, OperationOutcome<T>>(names.size)
        for (propertyName in names) results[propertyName]?.let { ordered[propertyName] = it }
        return ordered
    }

    private fun effectiveTimeout(descriptor: PropertyDescriptor, opSpec: OperationPipelineSpec): kotlin.time.Duration? =
        if (opSpec.suppressDescriptorQos) opSpec.defaultTimeout else descriptor.timeout ?: opSpec.defaultTimeout

    private fun effectiveRetry(descriptor: PropertyDescriptor, opSpec: OperationPipelineSpec) =
        if (opSpec.suppressDescriptorQos) opSpec.defaultRetry else descriptor.retryPolicy ?: opSpec.defaultRetry

    /**
     * Aggregates per-property QoS into one whole-sub-batch [OperationPolicy].
     *
     * - `timeout` follows the backend's [BatchExecutionMode]: a coalescing backend (one transaction)
     *   gets the **maximum** member budget; a sequential backend (the default fallback) gets the
     *   **sum**, so an N-property batch is not forced into one member's deadline. If any member is
     *   unbounded (no budget), so is the batch.
     * - `retry` is the group's shared policy (the batch is partitioned by retry policy upstream).
     * - `locks` are the union of member locks.
     *
     * Descriptor-level QoS is skipped when the profile suppresses it (see [operationPolicy]).
     */
    private fun batchPolicy(
        descriptors: Collection<PropertyDescriptor>,
        opSpec: OperationPipelineSpec,
        retry: RetryPolicy?,
    ): OperationPolicy {
        val timeouts = descriptors.map { effectiveTimeout(it, opSpec) }
        val timeout = when {
            timeouts.isEmpty() || timeouts.any { it == null } -> null
            opSpec.batchExecutionMode == BatchExecutionMode.Coalescing -> timeouts.filterNotNull().max()
            else -> timeouts.filterNotNull().reduce { left, right -> left + right }
        }
        return OperationPolicy(
            timeout = timeout,
            retry = retry,
            locks = descriptors.flatMap { it.requiredLocks },
        )
    }
}
