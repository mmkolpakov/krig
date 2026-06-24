package space.kscience.krig.storage.timeseries

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Row-level compression gate for polling/time-series flows.
 *
 * It is deliberately storage-neutral: apply it to chunks before a sink, or wrap a sink with
 * [compressing]. Dense double chunks use [numericDelta]; generic chunks use equality, with
 * numeric values compared by [numericDelta] when both sides are [Number]s.
 */
@Serializable
public data class RowsCompression(
    public val skipUnchangedRows: Boolean = true,
    public val skipUnchangedValues: Boolean = true,
    public val numericDelta: Double = 0.0,
    public val minIntervalMillis: Long = 0,
) {
    init {
        require(numericDelta >= 0.0) { "numericDelta must be non-negative, got $numericDelta" }
        require(minIntervalMillis >= 0) { "minIntervalMillis must be non-negative, got $minIntervalMillis" }
    }

    public companion object {
        public val Default: RowsCompression = RowsCompression()
        public val None: RowsCompression = RowsCompression(
            skipUnchangedRows = false,
            skipUnchangedValues = false,
            numericDelta = 0.0,
            minIntervalMillis = 0,
        )
    }
}

/** Compresses generic sparse rows by dropping repeated rows and optionally repeated values. */
public fun <T> TimeSeriesChunk<T>.compressRows(compression: RowsCompression = RowsCompression.Default): TimeSeriesChunk<T> {
    if (rows.isEmpty() || compression == RowsCompression.None) return this
    val compressed = ArrayList<TimeSeriesRow<T>>(rows.size)
    val previousValues: MutableMap<Name, T> = mutableMapOf()
    var previousQuality: DataQuality? = null
    var lastKeptTime: Instant? = null

    for (row in rows) {
        val values = if (compression.skipUnchangedValues) {
            row.values.filterValuesByName { name, value ->
                !valuesEquivalent(previousValues[name], value, compression.numericDelta)
            }
        } else {
            row.values
        }
        val qualityChanged = previousQuality != row.quality
        val rowChanged = !compression.skipUnchangedRows || values.isNotEmpty() || qualityChanged
        val intervalAllows = lastKeptTime.allows(row.time, compression)

        if (rowChanged && intervalAllows) {
            compressed += row.copy(values = values)
            row.values.forEach { (name, value) -> previousValues[name] = value }
            previousQuality = row.quality
            lastKeptTime = row.time
        }
    }
    return TimeSeriesChunk(series, compressed)
}

/**
 * Compresses dense double rows by dropping rows whose values and aggregate quality did not change.
 * Column-native: comparison reads straight from the column buffers against the last kept row, and the
 * result is selected column-major — no per-row [DoubleArray] or [DenseDoubleTimeSeriesRow] is allocated.
 */
public fun DenseDoubleTimeSeriesChunk.compressRows(
    compression: RowsCompression = RowsCompression.Default,
): DenseDoubleTimeSeriesChunk {
    if (rowCount == 0 || compression == RowsCompression.None) return this
    val keptRows = IntArray(rowCount)
    var keptCount = 0
    var lastKept = -1
    var previousQuality: DataQuality? = null
    var lastKeptTime: Instant? = null

    for (index in 0 until rowCount) {
        val valuesChanged = lastKept < 0 || series.indices.any { column ->
            !doubleEquivalent(value(lastKept, column), value(index, column), compression.numericDelta)
        }
        val aggregateQuality = aggregateQualityAt(index)
        val qualityChanged = previousQuality != aggregateQuality
        val rowChanged = !compression.skipUnchangedRows || valuesChanged || qualityChanged
        val intervalAllows = lastKeptTime.allows(times[index], compression)

        if (rowChanged && intervalAllows) {
            keptRows[keptCount++] = index
            lastKept = index
            previousQuality = aggregateQuality
            lastKeptTime = times[index]
        }
    }
    return selectRows(keptRows, keptCount)
}

/** Flow adapter for chunk pipelines. */
public fun <T> Flow<TimeSeriesChunk<T>>.compressRows(
    compression: RowsCompression = RowsCompression.Default,
): Flow<TimeSeriesChunk<T>> = map { it.compressRows(compression) }

/** Flow adapter for dense double chunk pipelines. */
public fun Flow<DenseDoubleTimeSeriesChunk>.compressDenseRows(
    compression: RowsCompression = RowsCompression.Default,
): Flow<DenseDoubleTimeSeriesChunk> = map { it.compressRows(compression) }

/** Sink wrapper for sparse chunks. */
public fun <T> TimeSeriesChunkSink<T>.compressing(
    compression: RowsCompression = RowsCompression.Default,
): TimeSeriesChunkSink<T> {
    val delegate = this
    return object : TimeSeriesChunkSink<T> {
        override suspend fun append(chunk: TimeSeriesChunk<T>) {
            delegate.append(chunk.compressRows(compression))
        }
    }
}

/** Sink wrapper for dense double chunks. */
public fun DenseDoubleTimeSeriesChunkSink.compressing(
    compression: RowsCompression = RowsCompression.Default,
): DenseDoubleTimeSeriesChunkSink {
    val delegate = this
    return object : DenseDoubleTimeSeriesChunkSink {
        override suspend fun append(chunk: DenseDoubleTimeSeriesChunk) {
            delegate.append(chunk.compressRows(compression))
        }
    }
}

private inline fun <K, V> Map<K, V>.filterValuesByName(predicate: (K, V) -> Boolean): Map<K, V> =
    entries.filter { (key, value) -> predicate(key, value) }.associate { it.toPair() }

private fun Instant?.allows(candidate: Instant, compression: RowsCompression): Boolean =
    this == null || compression.minIntervalMillis == 0L ||
            candidate - this >= compression.minIntervalMillis.milliseconds

private fun valuesEquivalent(previous: Any?, next: Any?, numericDelta: Double): Boolean {
    if (previous is Number && next is Number) {
        return doubleEquivalent(previous.toDouble(), next.toDouble(), numericDelta)
    }
    return previous == next
}

private fun doubleEquivalent(previous: Double, next: Double, delta: Double): Boolean =
    previous == next || (previous.isNaN() && next.isNaN()) || abs(previous - next) <= delta
