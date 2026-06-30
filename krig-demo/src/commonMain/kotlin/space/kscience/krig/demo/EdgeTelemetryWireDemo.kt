package space.kscience.krig.demo

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseBooleanTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseBooleanTimeSeriesRow
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import space.kscience.krig.storage.timeseries.DenseIntTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseIntTimeSeriesRow
import kotlin.time.Instant

private const val DEFAULT_EDGE_SAMPLE_COUNT: Int = 1_000
private const val WARMUP_PERIOD: Int = 250
private const val TEMPERATURE_BASE: Double = 20.0
private const val TEMPERATURE_STEP: Double = 0.01
private const val PRESSURE_BASE: Double = 1.0
private const val PRESSURE_STEP: Double = 0.001

internal data class EdgeTelemetryWirePayload(
    val analog: DenseDoubleTimeSeriesChunk,
    val registers: DenseIntTimeSeriesChunk,
    val coils: DenseBooleanTimeSeriesChunk,
)

/** Common edge payload: dense telemetry stays KMP; JVM-only code decides whether to export to Arrow. */
suspend fun edgeTelemetryWireDemo() {
    val payload = edgeTelemetryWirePayload(sampleCount = 64)
    val chunk = payload.analog
    val firstQuality = chunk.qualityAt(row = 0, seriesIndex = 0)

    println("=== Edge telemetry wire payload ===")
    println("  rows: ${chunk.rowCount}, series: ${chunk.series}")
    println("  register rows: ${payload.registers.rowCount}, coil rows: ${payload.coils.rowCount}")
    println("  first row aggregate quality: ${chunk.aggregateQualityAt(0).severity.label}")
    println("  first temperature quality code: ${firstQuality.code?.id}")
    println("\nDone - edge telemetry wire demo complete.")
}

internal fun edgeTelemetryWireChunk(sampleCount: Int = DEFAULT_EDGE_SAMPLE_COUNT): DenseDoubleTimeSeriesChunk {
    require(sampleCount > 0) { "sampleCount must be positive, got $sampleCount" }

    val temperature = "reactor.temperature".asName()
    val pressure = "reactor.pressure".asName()
    return DenseDoubleTimeSeriesChunk(
        series = listOf(temperature, pressure),
        rows = List(sampleCount) { i ->
            DenseDoubleTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(i.toLong()),
                values = doubleArrayOf(
                    TEMPERATURE_BASE + i * TEMPERATURE_STEP,
                    PRESSURE_BASE + i * PRESSURE_STEP,
                ),
                baselineQuality = if (i % WARMUP_PERIOD == 0) {
                    DataQuality(QualitySeverity.UNCERTAIN, QualityCode("sim.warmup"), detail = "settling")
                } else {
                    DataQuality.GOOD
                },
            )
        },
    )
}

internal fun edgeTelemetryWirePayload(sampleCount: Int = DEFAULT_EDGE_SAMPLE_COUNT): EdgeTelemetryWirePayload {
    val analog = edgeTelemetryWireChunk(sampleCount)
    val rpm = "drive.rpm.register".asName()
    val batch = "batch.sequence".asName()
    val running = "drive.running".asName()
    val interlock = "safety.interlock".asName()

    val registers = DenseIntTimeSeriesChunk(
        series = listOf(rpm, batch),
        rows = List(sampleCount) { i ->
            DenseIntTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(i.toLong()),
                values = intArrayOf(1_200 + i, i),
                baselineQuality = if (i % WARMUP_PERIOD == 0) {
                    DataQuality(QualitySeverity.UNCERTAIN, QualityCode("sim.register-warmup"))
                } else {
                    DataQuality.GOOD
                },
            )
        },
    )
    val coils = DenseBooleanTimeSeriesChunk(
        series = listOf(running, interlock),
        rows = List(sampleCount) { i ->
            DenseBooleanTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(i.toLong()),
                values = booleanArrayOf(i > 0, i % 10 != 0),
                baselineQuality = if (i == 0) {
                    DataQuality(QualitySeverity.UNCERTAIN, QualityCode("sim.coil-startup"))
                } else {
                    DataQuality.GOOD
                },
            )
        },
    )
    return EdgeTelemetryWirePayload(analog = analog, registers = registers, coils = coils)
}
