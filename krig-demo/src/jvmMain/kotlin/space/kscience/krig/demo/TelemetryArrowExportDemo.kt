package space.kscience.krig.demo

import space.kscience.krig.arrow.writeArrowIpcFile
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
    val chunk = edgeTelemetryWireChunk(SAMPLE_COUNT)
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
