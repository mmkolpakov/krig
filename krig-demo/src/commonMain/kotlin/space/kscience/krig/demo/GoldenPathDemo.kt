package space.kscience.krig.demo

import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.data.observed
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.dsl.device

/**
 * Golden path: typed data plane with data quality and an unboxed sampler — no `Meta` literals in
 * the driver and no `@OptIn(KrigPerformancePitfall)` on the read path. The driver speaks `Double`;
 * quality rides as a typed [DataQuality] and never needs Meta parsing on read.
 */
object GoldenSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val temperature by doubleProperty()
}

val GoldenManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.demo.golden",
    contract = GoldenSpec,
    version = "1.0.0-alpha-3",
)

/** Typed in-memory backend: rpm with an unboxed sampler, temperature with quality. */
fun goldenBackend() = deviceBackend {
    var rpm = 0.0
    val rpmSampler = doubleSampler(capacity = 64)

    reader(GoldenSpec.rpm) { rpm }
    writer(GoldenSpec.rpm) { value ->
        rpm = value
        rpmSampler.publishDouble(value)
    }
    sampler(GoldenSpec.rpm) { rpmSampler }

    observedReader(GoldenSpec.temperature) {
        val celsius = 60.0 + rpm / 100.0
        val quality = if (celsius > 70.0) {
            DataQuality(QualitySeverity.UNCERTAIN, detail = "over-temp")
        } else {
            DataQuality.GOOD
        }
        observed(celsius, quality = quality)
    }
}

suspend fun goldenPathDemo() {
    val ctx = demoContext("golden-path")
    val sensor = device("sensor", goldenBackend(), ctx) {
        manifest(GoldenManifest)
    }

    println("=== Golden path (typed data plane) ===")

    // Typed writes/reads — no Meta, no @OptIn(KrigPerformancePitfall).
    sensor.write(GoldenSpec.rpm, 1_500.0)
    println("  rpm (typed): ${sensor.read(GoldenSpec.rpm)}")

    // Typed batch read: quality is a typed DataQuality, surfaced without parsing Meta.
    val batch = sensor.readBatchOutcome(listOf(GoldenSpec.rpm.name, GoldenSpec.temperature.name))
    for ((property, outcome) in batch) {
        when (outcome) {
            is OperationOutcome.Ok -> println("  $property quality=${outcome.value.quality.shortLabel}")
            is OperationOutcome.Fail -> println("  $property failed: ${outcome.fault.faultType}")
        }
    }

    // Unboxed sampler snapshot — no boxing of doubles.
    val sampler = sensor.doubleSampler(GoldenSpec.rpm)
    println("  rpm samples (unboxed): ${sampler?.snapshotDoubleArray()?.toList()}")

    sensor.close()
    ctx.close()
    println("\nDone - golden path demo complete.")
}
