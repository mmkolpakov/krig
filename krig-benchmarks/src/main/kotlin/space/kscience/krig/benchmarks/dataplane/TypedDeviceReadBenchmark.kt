@file:Suppress("unused")

package space.kscience.krig.benchmarks.dataplane

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.NoOpAuditService
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.assembly.AcquisitionConnectors
import space.kscience.krig.assembly.DataAcquisitionConfiguration
import space.kscience.krig.assembly.dataAcquisition
import space.kscience.krig.assembly.deviceTreeAcquisitionReader
import space.kscience.krig.assembly.pollTimer
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.dsl.device
import kotlin.time.Duration.Companion.milliseconds

/**
 * End-to-end typed read against a fully assembled [Device] (pipeline + backend) and a
 * pollTimer-driven acquisition loop. Covers the typed data-plane hot path and the acquisition
 * fan-out that micro batch benchmarks do not exercise.
 */
@State(Scope.Benchmark)
open class TypedDeviceReadBenchmark {
    private lateinit var context: Context
    private lateinit var sensor: Device
    private lateinit var config: DataAcquisitionConfiguration

    @Setup
    open fun setup() {
        context = Context("bench-typed-read") {
            plugin(AllowAllAuthorizationService)
            plugin(NoOpAuditService)
        }
        sensor = runBlocking {
            device("stand", benchSensorBackend(), context) { manifest(BenchSensorManifest) }
        }
        config = dataAcquisition {
            source("stand", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
            tag("temperature").from("stand", "temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "temperature") }
        }
    }

    @Benchmark
    open fun typedPointRead(blackhole: Blackhole): Double = runBlocking {
        sensor.read(BenchSensorSpec.rpm).also(blackhole::consume)
    }

    @Benchmark
    open fun typedBatchRead(blackhole: Blackhole): Int = runBlocking {
        sensor.readBatchOutcome(listOf(BenchSensorSpec.rpm.name, BenchSensorSpec.temperature.name))
            .size.also(blackhole::consume)
    }

    @Benchmark
    open fun acquisitionPollTimer(blackhole: Blackhole): Int = runBlocking {
        val reader = deviceTreeAcquisitionReader(mapOf("stand".asName() to sensor))
        val ticks: Flow<Unit> = flow { repeat(256) { emit(Unit) } }
        config.pollTimer("fast", ticks, reader).count().also(blackhole::consume)
    }
}

internal object BenchSensorSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val temperature by doubleProperty()
}

internal val BenchSensorManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.benchmarks.sensor",
    contract = BenchSensorSpec,
    version = "1.0.0",
)

internal fun benchSensorBackend() = deviceBackend {
    var rpm = 1_200.0
    reader(BenchSensorSpec.rpm) { rpm }
    writer(BenchSensorSpec.rpm) { value -> rpm = value }
    reader(BenchSensorSpec.temperature) { 60.0 + rpm / 100.0 }
}
