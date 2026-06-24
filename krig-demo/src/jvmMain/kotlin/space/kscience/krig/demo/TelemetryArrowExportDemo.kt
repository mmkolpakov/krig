package space.kscience.krig.demo

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.arrow.writeArrowIpcFile
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

private const val SAMPLE_COUNT: Int = 1_000
private const val WARMUP_PERIOD: Int = 250
private const val TEMPERATURE_BASE: Double = 20.0
private const val TEMPERATURE_STEP: Double = 0.01
private const val PRESSURE_BASE: Double = 1.0
private const val PRESSURE_STEP: Double = 0.001

/**
 * Minimal analytics export scenario: collect dense telemetry, then write one file
 * that pandas / Polars / DuckDB / Spark can open directly. No Arrow types appear in user code.
 *
 * Lives in `krig-demo` (not the `krig-arrow` library) so the export library ships without sample
 * `main` entry points.
 */
public fun main() {
    val temperature = "reactor.temperature".asName()
    val pressure = "reactor.pressure".asName()

    val chunk = DenseDoubleTimeSeriesChunk(
        series = listOf(temperature, pressure),
        rows = List(SAMPLE_COUNT) { i ->
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

    val path = Path.of("build", "telemetry.arrow")
    chunk.writeArrowIpcFile(
        path,
        schemaMetadata = mapOf(
            "krig.source" to "reactor",
            "krig.series.temperature.unit" to "degC",
            "krig.series.pressure.unit" to "bar",
        ),
    )

    println("Exported ${chunk.rows.size} rows to ${path.toAbsolutePath()} (${Files.size(path)} bytes)")
}
