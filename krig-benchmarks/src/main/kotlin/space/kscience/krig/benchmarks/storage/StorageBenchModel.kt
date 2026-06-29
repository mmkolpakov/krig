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
