package space.kscience.krig.benchmarks.storage

import kotlin.time.Duration
import kotlin.time.Instant
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import space.kscience.krig.storage.timeseries.TimeSeriesSample

internal data class ReadStats(val duration: Duration, val count: Int, val checksum: Double)

internal fun propertyMessage(
    row: Int,
    tag: Int,
    property: Name,
    source: Name,
    workload: MatrixWorkload,
): PropertyChangedMessage = timeSeriesSample(row, tag, workload, property).toPropertyChangedMessage(source)

internal fun timeSeriesSample(
    row: Int,
    tag: Int,
    workload: MatrixWorkload,
    series: Name = tagName(tag),
): TimeSeriesSample<Double> =
    TimeSeriesSample(
        series = series,
        value = workload.valueAt(row, tag),
        time = Instant.fromEpochMilliseconds(row.toLong() * 1_000L),
    )

internal fun TimeSeriesSample<Double>.toPropertyChangedMessage(source: Name): PropertyChangedMessage =
    PropertyChangedMessage(
        time = time,
        property = series,
        value = MetaConverter.double.convert(value),
        sourceDevice = source,
    )

internal fun tagName(tag: Int): Name = "tag$tag".asName()

internal fun MatrixWorkload.denseDoubleChunk(): DenseDoubleTimeSeriesChunk {
    val series = List(tags, ::tagName)
    val rows = List(rows) { row ->
        DenseDoubleTimeSeriesRow(
            time = Instant.fromEpochMilliseconds(row.toLong() * 1_000L),
            values = DoubleArray(tags) { tag -> valueAt(row, tag) },
        )
    }
    return DenseDoubleTimeSeriesChunk(series, rows)
}
