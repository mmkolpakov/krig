package space.kscience.krig.benchmarks.storage

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.time.Duration

internal val reportLocale: Locale = Locale.US

internal data class BenchConfig(
    val controlsEvents: Int = envInt("KRIG_STORAGE_BENCH_CONTROLS_EVENTS") ?: 100_000,
    val matrixTags: Int = envInt("KRIG_STORAGE_BENCH_TAGS") ?: 3_000,
    val matrixRows: Int = envInt("KRIG_STORAGE_BENCH_ROWS") ?: 30,
    val referenceRowsTags: Int = envInt("KRIG_STORAGE_BENCH_REFERENCE_TAGS") ?: 50,
    val referenceRowsRows: Int = envInt("KRIG_STORAGE_BENCH_REFERENCE_ROWS") ?: 2_000,
    val batchSize: Int = envInt("KRIG_STORAGE_BENCH_BATCH") ?: 1_000,
    val referenceDelta: Double = System.getenv("KRIG_STORAGE_BENCH_REFERENCE_DELTA")?.toDoubleOrNull() ?: 0.1,
    val matrixDeadband: Double = System.getenv("KRIG_STORAGE_BENCH_MATRIX_DEADBAND")?.toDoubleOrNull() ?: 0.05,
    val runTimescale: Boolean = System.getenv("KRIG_STORAGE_BENCH_TIMESCALE").toBooleanEnv(),
    val timescaleImage: String = System.getenv("KRIG_STORAGE_BENCH_TIMESCALE_IMAGE")
        ?: "timescale/timescaledb:latest-pg16",
    val root: Path = Path("build/krig-benchmarks"),
)

internal class MatrixWorkload private constructor(
    val id: String,
    val tags: Int,
    val rows: Int,
    private val sample: (row: Int, tag: Int) -> Double,
) {
    val values: Int get() = tags * rows

    fun valueAt(row: Int, tag: Int): Double = sample(row, tag)

    companion object {
        fun deterministic(id: String, tags: Int, rows: Int): MatrixWorkload =
            MatrixWorkload(id, tags, rows) { row, tag ->
                tag * 0.001 + row * 0.02 + kotlin.math.sin((row + tag % 17) * 0.1) * 0.05
            }

        fun randomWalk(
            id: String,
            tags: Int,
            rows: Int,
            seed: Int,
            stepFrom: Double,
            stepUntil: Double,
        ): MatrixWorkload {
            val random = Random(seed)
            val current = DoubleArray(tags)
            val samples = Array(rows) {
                DoubleArray(tags) { tag ->
                    current[tag] += random.nextDouble(stepFrom, stepUntil)
                    current[tag]
                }
            }
            return MatrixWorkload(id, tags, rows) { row, tag -> samples[row][tag] }
        }
    }
}

internal data class BenchResult(
    val scenario: String,
    val rows: Int,
    val values: Int,
    val write: Duration,
    val read: Duration,
    val bytes: Long?,
    val note: String,
)

internal data class ReferenceResult(
    val scenario: String,
    val values: Int,
    val write: String,
    val read: String,
    val bytes: String,
    val bytesPerValue: String,
    val note: String,
)

internal val controlsReferences: List<ReferenceResult> = listOf(
    ReferenceResult(
        scenario = "controls.h2.event-json",
        values = 100_000,
        write = "2.2s",
        read = "789ms",
        bytes = "16.9 MB",
        bytesPerValue = "169",
        note = "Exposed + H2, one property, full DeviceMessage JSON",
    ),
    ReferenceResult(
        scenario = "controls.timescale.event-json",
        values = 100_000,
        write = "28.5s",
        read = "7.1s",
        bytes = "38,223,388 delta",
        bytesPerValue = "382.23",
        note = "Exposed + TimescaleDB, no JDBC rewrite",
    ),
    ReferenceResult(
        scenario = "controls.timescale.event-json.rewrite",
        values = 100_000,
        write = "5.23s",
        read = "1.0s",
        bytes = "38,003,732 delta",
        bytesPerValue = "380.04",
        note = "Exposed + TimescaleDB, reWriteBatchedInserts=true",
    ),
    ReferenceResult(
        scenario = "controls.rows.deflate",
        values = 100_000,
        write = "531ms",
        read = "171ms",
        bytes = "1,040,198",
        bytesPerValue = "10.40",
        note = "50 tags x 2000 rows, no value filtering",
    ),
    ReferenceResult(
        scenario = "controls.rows.deflate.delta",
        values = 100_000,
        write = "67ms",
        read = "29ms",
        bytes = "212,768",
        bytesPerValue = "2.13",
        note = "50 tags x 2000 rows, numeric margin 0.1",
    ),
)

internal val controlsLocalRerun: List<ReferenceResult> = listOf(
    ReferenceResult(
        scenario = "controls.local.h2.event-json",
        values = 100_000,
        write = "3.098s",
        read = "1.166s",
        bytes = "17,387,520",
        bytesPerValue = "173.88",
        note = "local rerun, Exposed + H2, one property, full DeviceMessage JSON",
    ),
    ReferenceResult(
        scenario = "controls.local.timescale.event-json.rewrite",
        values = 100_000,
        write = "6.246s",
        read = "1.839s",
        bytes = "37,796,360 delta",
        bytesPerValue = "377.96",
        note = "local rerun, TimescaleDB, reWriteBatchedInserts=true",
    ),
    ReferenceResult(
        scenario = "controls.local.rows.deflate",
        values = 100_000,
        write = "492.12ms",
        read = "194.56ms",
        bytes = "1,040,198",
        bytesPerValue = "10.40",
        note = "local rerun, 50 tags x 2000 rows, no value filtering",
    ),
    ReferenceResult(
        scenario = "controls.local.rows.deflate.repeating",
        values = 100_000,
        write = "279.31ms",
        read = "86.26ms",
        bytes = "1,040,198",
        bytesPerValue = "10.40",
        note = "local rerun, 50 tags x 2000 rows, skip exact repeats",
    ),
    ReferenceResult(
        scenario = "controls.local.rows.deflate.delta",
        values = 100_000,
        write = "59.40ms",
        read = "21.72ms",
        bytes = "212,768",
        bytesPerValue = "2.13",
        note = "local rerun, 50 tags x 2000 rows, numeric margin 0.1",
    ),
)

internal fun ReferenceResult.markdownRow(): String =
    "| $scenario | $values | $write | $read | $bytes | $bytesPerValue | $note |"

internal fun BenchResult.markdownRow(): String {
    val bytesText = bytes?.toString().orEmpty()
    val bytesPerValue = bytes?.let { "%.2f".format(reportLocale, it.toDouble() / values.coerceAtLeast(1)) }.orEmpty()
    return "| $scenario | $rows | $values | ${write.pretty()} | ${read.pretty()} | $bytesText | $bytesPerValue | $note |"
}

internal fun Duration.pretty(): String = when {
    inWholeSeconds > 0 -> "${"%.3f".format(reportLocale, inWholeNanoseconds / 1_000_000_000.0)}s"
    inWholeMilliseconds > 0 -> "${"%.2f".format(reportLocale, inWholeNanoseconds / 1_000_000.0)}ms"
    else -> "${inWholeMicroseconds}us"
}
