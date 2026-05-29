@file:Suppress("unused")

package space.kscience.krig.benchmarks.storage

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import space.kscience.krig.storage.timeseries.RowsCompression
import space.kscience.krig.storage.timeseries.TimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeriesRow
import space.kscience.krig.storage.timeseries.compressRows
import kotlin.time.Instant

/** Row-level compression gates used before storage sinks. */
@State(Scope.Benchmark)
open class RowsCompressionBenchmark {
    private lateinit var sparse: TimeSeriesChunk<Double>
    private lateinit var sparseQuality: TimeSeriesChunk<Double>
    private lateinit var dense: DenseDoubleTimeSeriesChunk

    @Setup
    open fun setup() {
        val series = List(32) { index -> "pv$index".asName() }
        sparse = sparseChunk(series)
        sparseQuality = sparseQualityChunk(series)
        dense = denseChunk(series)
    }

    @Benchmark
    open fun sparseMinInterval(blackhole: Blackhole): Int {
        val compressed = sparse.compressRows(RowsCompression(minIntervalMillis = 10))
        blackhole.consume(compressed)
        return compressed.rows.size
    }

    @Benchmark
    open fun sparseQualityMinInterval(blackhole: Blackhole): Int {
        val compressed = sparseQuality.compressRows(RowsCompression(minIntervalMillis = 10))
        blackhole.consume(compressed)
        return compressed.rows.size
    }

    @Benchmark
    open fun denseDeadband(blackhole: Blackhole): Int {
        val compressed = dense.compressRows(RowsCompression(numericDelta = 0.1))
        blackhole.consume(compressed)
        return compressed.rows.size
    }
}

private fun sparseChunk(series: List<Name>): TimeSeriesChunk<Double> =
    TimeSeriesChunk(
        series = series,
        rows = List(512) { row ->
            TimeSeriesRow(
                time = Instant.fromEpochMilliseconds(row.toLong()),
                values = series.associateWith { pv -> (row / 4).toDouble() + pv.toString().length },
            )
        },
    )

private fun sparseQualityChunk(series: List<Name>): TimeSeriesChunk<Double> {
    val bad = DataQuality(QualitySeverity.BAD)
    return TimeSeriesChunk(
        series = series,
        rows = List(512) { row ->
            TimeSeriesRow(
                time = Instant.fromEpochMilliseconds(row.toLong()),
                values = series.associateWith { 1.0 },
                quality = if (row >= 256) bad else DataQuality.GOOD,
            )
        },
    )
}

private fun denseChunk(series: List<Name>): DenseDoubleTimeSeriesChunk =
    DenseDoubleTimeSeriesChunk(
        series = series,
        rows = List(512) { row ->
            DenseDoubleTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(row.toLong()),
                values = DoubleArray(series.size) { column ->
                    (row / 3).toDouble() + column * 0.01
                },
            )
        },
    )
