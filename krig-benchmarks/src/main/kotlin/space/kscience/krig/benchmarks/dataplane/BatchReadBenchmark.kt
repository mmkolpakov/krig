@file:Suppress("unused")
@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.benchmarks.dataplane

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.pipeline.PipelineDevice
import space.kscience.krig.dsl.BackendDevice
import space.kscience.krig.dsl.DescriptorSource
import kotlin.time.Clock

/** Sequential observed fallback against a physical batch read. */
@State(Scope.Benchmark)
open class BatchReadBenchmark {
    private lateinit var env: BackendEnvironment
    private lateinit var properties: List<PropertyDescriptor>
    private lateinit var propertyNames: List<Name>
    private lateinit var sequential: DeviceBackend
    private lateinit var batched: DeviceBackend
    private lateinit var sequentialBound: BoundDeviceBackend
    private lateinit var batchedBound: BoundDeviceBackend
    private lateinit var pipelinedDevice: Device

    @Setup
    open fun setup() {
        env = benchmarkEnvironment()
        properties = List(128) { index ->
            PropertyDescriptor(
                name = "p$index".asName(),
                kind = PropertyKind.PHYSICAL,
                valueTypeId = TypeIds.DOUBLE,
            )
        }
        propertyNames = properties.map { it.name }
        sequential = SequentialBackend()
        batched = CoalescingBackend()
        sequentialBound = sequential.bind(env)
        batchedBound = batched.bind(env)
        val backendDevice = BackendDevice(
            backend = batched,
            name = "bench.pipeline".asName(),
            context = Context("bench-pipeline-batch"),
            descriptorSource = DescriptorSource.of(properties.associateBy { it.name }),
        )
        pipelinedDevice = PipelineDevice(backendDevice)
    }

    @Benchmark
    open fun sequentialObservedBatch(blackhole: Blackhole): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        runBlocking {
            sequentialBound.readBatchObserved(properties).also(blackhole::consume)
        }

    @Benchmark
    open fun coalescedObservedBatch(blackhole: Blackhole): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        runBlocking {
            batchedBound.readBatchObserved(properties).also(blackhole::consume)
        }

    @Benchmark
    open fun pipelinedDeviceObservedBatch(blackhole: Blackhole): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        runBlocking {
            pipelinedDevice.readBatchOutcome(propertyNames).also(blackhole::consume)
        }
}

private open class SequentialBackend : DeviceBackend {
    override fun bind(environment: BackendEnvironment): BoundDeviceBackend =
        sequentialBoundBackend(environment)

    protected fun sequentialBoundBackend(environment: BackendEnvironment): BoundDeviceBackend {
        val boundEnvironment = environment
        return object : BoundDeviceBackend {
            override val environment: BackendEnvironment = boundEnvironment

            override suspend fun read(property: PropertyDescriptor): Meta =
                metaOf(42.0)

            override suspend fun write(property: PropertyDescriptor, value: Meta) = Unit

            override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta? =
                throw OperationFaultException(
                    GenericOperationFault(
                        faultType = OperationFaultTypes.UnknownAction,
                        message = "No benchmark action.",
                    ),
                )

            override fun close() = Unit
        }
    }
}

private class CoalescingBackend : SequentialBackend() {
    override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
        val boundEnvironment = environment
        return object : BoundDeviceBackend by sequentialBoundBackend(boundEnvironment) {
            override val environment: BackendEnvironment = boundEnvironment

            override suspend fun readBatchObserved(
                properties: Collection<PropertyDescriptor>,
            ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
                properties.associate { property ->
                    property.name to OperationOutcome.Ok(
                        ObservedValue(metaOf(42.0), boundEnvironment.clock.now(), DataQuality.GOOD),
                    )
                }
        }
    }
}

internal fun benchmarkEnvironment(): BackendEnvironment =
    BackendEnvironment(
        context = Context("bench-device"),
        name = "bench.device".asName(),
        clock = Clock.System,
    )
