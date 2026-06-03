@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
)

package space.kscience.krig.core.pipeline

import kotlin.time.TimeSource
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
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
    private val lockRegistry: ResourceLockRegistry,
    private val timeSource: TimeSource,
) {
    private val tracker: OperationTracker? = delegate as? OperationTracker

    private val readExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Read)
    }
    private val writeExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Write)
    }
    private val actionExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        compileSharedExecutor(OperationKinds.Action)
    }

    private fun operationSpec(kind: OperationKind): OperationPipelineSpec =
        operationSpecs[kind] ?: OperationPipelineSpec.Empty

    private fun compileSharedExecutor(
        kind: OperationKind,
    ): suspend (OperationPlan, Any?) -> OperationOutcome<Any?> {
        val opSpec = operationSpec(kind)
        return compileOperationExecutor(
            gates = opSpec.gates,
            observers = opSpec.observers,
            registry = lockRegistry,
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

    fun <T> compileReader(spec: DevicePropertyContract<T>): TypedReader<T> {
        val raw = delegate.reader(spec)
        val opSpec = operationSpec(OperationKinds.Read)
        val context = OperationContext(OperationKinds.Read, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val decorated = readDecorators.fold(raw) { acc, dec -> dec.decorate(spec, acc) }
        val plan = OperationPlan(context, policy) {
            catchingOperationOutcome { decorated.read() }
        }
        return object : OutcomeTypedReader<T> {
            override suspend fun readOutcome(): OperationOutcome<T> =
                trackedOperation(tracker) {
                    readExecutor(plan, Unit).castOutcome()
                }

            override suspend fun read(): T = readOutcome().getOrThrow()
        }
    }

    fun <T> compileWriter(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        val raw = delegate.writer(spec)
        val opSpec = operationSpec(OperationKinds.Write)
        val context = OperationContext(OperationKinds.Write, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
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

    fun <I, O> compileAction(spec: DeviceActionContract<I, O>): suspend (I) -> O? {
        val opSpec = operationSpec(OperationKinds.Action)
        val context = OperationContext(OperationKinds.Action, spec.name, spec.descriptor, hostName)
        val policy = operationPolicy(spec.descriptor, opSpec)
        val plan = OperationPlan(context, policy) { input ->
            catchingOperationOutcome {
                val argMeta = if (input != null) spec.inputConverter.convert(input.castPayload()) else null
                val resultMeta = delegate.execute(spec.name, argMeta)
                if (resultMeta != null) spec.outputConverter.read(resultMeta) else null
            }
        }
        return { input ->
            val outcome = trackedOperation(tracker) { actionExecutor(plan, input).castOutcome<O?>() }
            outcome.getOrThrow()
        }
    }

    suspend fun <T> pipelinedSingleRead(
        propertyName: Name,
        operation: String,
        terminal: suspend () -> OperationOutcome<T>,
    ): OperationOutcome<T> {
        val descriptor = delegate.propertySpec(propertyName)?.descriptor
            ?: return unknownProperty(propertyName, operation)
        val opSpec = operationSpec(OperationKinds.Read)
        val context = OperationContext(OperationKinds.Read, propertyName, descriptor, hostName)
        val policy = operationPolicy(descriptor, opSpec)
        val plan = OperationPlan(context, policy) {
            terminal().eraseOutcome()
        }
        return trackedOperation(tracker) {
            readExecutor(plan, Unit).castOutcome()
        }
    }

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
        if (eligible.isEmpty()) return results

        val policy = batchPolicy(eligible.values, opSpec)
        val plan = OperationPlan(
            context = OperationContext(kind, operationName, batchDescriptor(operationName), hostName),
            policy = policy,
        ) {
            OperationOutcome.Ok(terminal(eligible.keys)).eraseOutcome()
        }
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
