package space.kscience.krig.demo

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.time.Instant

private const val DEFAULT_EDGE_SAMPLE_COUNT: Int = 1_000
private const val WARMUP_PERIOD: Int = 250
private const val TEMPERATURE_BASE: Double = 20.0
private const val TEMPERATURE_STEP: Double = 0.01
private const val PRESSURE_BASE: Double = 1.0
private const val PRESSURE_STEP: Double = 0.001

/** Common edge payload: dense telemetry stays KMP; JVM-only code decides whether to export to Arrow. */
suspend fun edgeTelemetryWireDemo() {
    val chunk = edgeTelemetryWireChunk(sampleCount = 64)
    val firstQuality = chunk.qualityAt(row = 0, seriesIndex = 0)

    println("=== Edge telemetry wire payload ===")
    println("  rows: ${chunk.rowCount}, series: ${chunk.series}")
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
