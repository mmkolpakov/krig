@file:Suppress("unused")

package space.kscience.krig.benchmarks.storage

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.arrow.ArrowCompression
import space.kscience.krig.arrow.writeArrowIpc
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import kotlin.time.Instant

/**
 * Arrow IPC export throughput for dense double telemetry.
 *
 * Note: Apache Arrow Java needs `--add-opens=java.base/java.nio=ALL-UNNAMED` and
 * `--enable-native-access=ALL-UNNAMED` on the forked JMH JVM. Compares ZSTD against uncompressed
 * to expose the compression cost; file-size ratios are reported by [main] in `ArrowSizeReport`.
 */
@State(Scope.Benchmark)
open class ArrowExportBenchmark {
    private lateinit var dense: DenseDoubleTimeSeriesChunk

    @Setup
    open fun setup() {
        dense = denseExportChunk(List(32) { "pv$it".asName() }, rowCount = 4_096)
    }

    @Benchmark
    open fun writeZstd(blackhole: Blackhole): Int = exportSize(dense, ArrowCompression.ZSTD).also(blackhole::consume)

    @Benchmark
    open fun writeNone(blackhole: Blackhole): Int = exportSize(dense, ArrowCompression.NONE).also(blackhole::consume)
}

internal fun denseExportChunk(series: List<Name>, rowCount: Int): DenseDoubleTimeSeriesChunk =
    DenseDoubleTimeSeriesChunk(
        series = series,
        rows = List(rowCount) { row ->
            DenseDoubleTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(row.toLong()),
                values = DoubleArray(series.size) { column -> (row / 3).toDouble() + column * 0.01 },
            )
        },
    )

internal fun exportSize(chunk: DenseDoubleTimeSeriesChunk, compression: ArrowCompression): Int {
    val sink = ByteArrayOutputStream()
    Channels.newChannel(sink).use { channel -> chunk.writeArrowIpc(channel, compression) }
    return sink.size()
}
