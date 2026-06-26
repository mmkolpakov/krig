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
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.pipeline.PipelineDevice
import space.kscience.krig.dsl.BackendDevice
import space.kscience.krig.dsl.DescriptorSource
import kotlin.time.Clock

/** Sequential observed fallback against a physical batch read. */
@State(Scope.Benchmark)
open class BatchReadBenchmark {
    private lateinit var env: DeviceEnvironment
    private lateinit var properties: List<PropertyDescriptor>
    private lateinit var propertyNames: List<Name>
    private lateinit var sequential: DeviceBackend
    private lateinit var batched: DeviceBackend
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
            context(env) {
                sequential.readBatchObserved(properties).also(blackhole::consume)
            }
        }

    @Benchmark
    open fun coalescedObservedBatch(blackhole: Blackhole): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        runBlocking {
            context(env) {
                batched.readBatchObserved(properties).also(blackhole::consume)
            }
        }

    @Benchmark
    open fun pipelinedDeviceObservedBatch(blackhole: Blackhole): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        runBlocking {
            pipelinedDevice.readBatchOutcome(propertyNames).also(blackhole::consume)
        }
}

private open class SequentialBackend : DeviceBackend {
    context(env: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
        OperationOutcome.Ok(metaOf(42.0))

    context(env: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    context(env: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownAction,
                message = "No benchmark action.",
            ),
        )

    override fun close() = Unit
}

private class CoalescingBackend : SequentialBackend() {
    context(env: DeviceEnvironment)
    override suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        properties.associate { property ->
            property.name to OperationOutcome.Ok(
                ObservedValue(metaOf(42.0), env.clock.now(), DataQuality.GOOD),
            )
        }
}

internal fun benchmarkEnvironment(): DeviceEnvironment = object : DeviceEnvironment {
    override val clock: Clock = Clock.System
    override val name: Name = "bench.device".asName()
}
