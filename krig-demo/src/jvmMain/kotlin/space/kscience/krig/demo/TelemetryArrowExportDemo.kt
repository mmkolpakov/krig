package space.kscience.krig.demo

import space.kscience.krig.arrow.writeArrowIpcFile
import space.kscience.krig.storage.timeseries.decodeDenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.toDenseTimeSeriesEnvelope
import java.nio.file.Files
import java.nio.file.Path

private const val SAMPLE_COUNT: Int = 1_000

/**
 * Minimal analytics export scenario: collect dense telemetry, then write one file
 * that pandas / Polars / DuckDB / Spark can open directly. No Arrow types appear in user code.
 *
 * Lives in `krig-demo` (not the `krig-arrow` library) so the export library ships without sample
 * `main` entry points.
 */
public fun main() {
    val path = Path.of("build", "telemetry.arrow")
    val bytes = writeTelemetryArrowExport(path)

    println("Exported $SAMPLE_COUNT rows to ${path.toAbsolutePath()} ($bytes bytes)")
}

internal fun writeTelemetryArrowExport(
    path: Path,
    sampleCount: Int = SAMPLE_COUNT,
): Long {
    val chunk = edgeTelemetryWireChunk(sampleCount)
        .toDenseTimeSeriesEnvelope()
        .decodeDenseDoubleTimeSeriesChunk()
    chunk.writeArrowIpcFile(
        path,
        schemaMetadata = mapOf(
            "krig.source" to "reactor",
            "krig.series.temperature.unit" to "degC",
            "krig.series.pressure.unit" to "bar",
        ),
    )
    return Files.size(path)
}
