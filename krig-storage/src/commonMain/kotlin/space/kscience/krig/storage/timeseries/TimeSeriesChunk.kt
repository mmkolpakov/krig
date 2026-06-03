package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.Name
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.api.data.DataQuality
import kotlin.time.Instant

/** Row-oriented chunk for time-series rows storage. */
public data class TimeSeriesChunk<T>(
    public val series: List<Name>,
    public val rows: List<TimeSeriesRow<T>>,
)

public data class TimeSeriesRow<T>(
    public val time: Instant,
    public val values: Map<Name, T>,
    public val quality: DataQuality = DataQuality.GOOD,
)

public interface TimeSeriesChunkSink<T> {
    public suspend fun append(chunk: TimeSeriesChunk<T>)
}

/**
 * Dense primitive chunk for high-frequency double telemetry. Storage is column-major: each series
 * is a contiguous [Float64Buffer], with a parallel per-row quality sidecar. Build it from rows for
 * convenience; read it through [column]/[value] on hot paths and [rows] for row-shaped tooling.
 */
public class DenseDoubleTimeSeriesChunk private constructor(
    public val series: List<Name>,
    public val times: List<Instant>,
    private val columns: List<Float64Buffer>,
    private val rowQuality: List<DenseRowQuality>,
) {
    public constructor(
        series: List<Name>,
        rows: List<DenseDoubleTimeSeriesRow>,
    ) : this(series, rows.map { it.time }, transposeColumns(series, rows), rows.map { it.quality })

    public val rowCount: Int get() = times.size

    /** Values of [seriesIndex] across all rows as a contiguous unboxed buffer for KMath algebras. */
    public fun column(seriesIndex: Int): Float64Buffer {
        require(seriesIndex in series.indices) {
            "Series index must be inside 0 until ${series.size}, got $seriesIndex."
        }
        return columns[seriesIndex]
    }

    public fun value(row: Int, seriesIndex: Int): Double = columns[seriesIndex][row]

    public fun qualityAt(row: Int, seriesIndex: Int): DataQuality = rowQuality[row].at(seriesIndex)

    public fun aggregateQualityAt(row: Int): DataQuality = rowQuality[row].aggregate

    /** Reconstructs row [index] from the column store; allocates one value array per call. */
    public fun row(index: Int): DenseDoubleTimeSeriesRow = DenseDoubleTimeSeriesRow(
        time = times[index],
        values = DoubleArray(series.size) { columns[it][index] },
        baselineQuality = rowQuality[index].baseline,
        qualityOverrides = rowQuality[index].overrides,
    )

    /** Row-shaped view materialised on first access; prefer [column]/[value] for numeric scans. */
    public val rows: List<DenseDoubleTimeSeriesRow> by lazy { List(rowCount, ::row) }

    /**
     * Builds a chunk from the first [count] entries of [keptRows] (row indices into this chunk),
     * selecting column-major without materialising rows. Backs column-native row compression.
     */
    internal fun selectRows(keptRows: IntArray, count: Int): DenseDoubleTimeSeriesChunk {
        if (count == rowCount) return this
        val selectedColumns = List(series.size) { column ->
            val source = columns[column]
            Float64Buffer(count) { source[keptRows[it]] }
        }
        val selectedTimes = List(count) { times[keptRows[it]] }
        val selectedQuality = List(count) { rowQuality[keptRows[it]] }
        return DenseDoubleTimeSeriesChunk(series, selectedTimes, selectedColumns, selectedQuality)
    }
}

internal class DenseRowQuality(
    val baseline: DataQuality,
    val overrides: Map<Int, DataQuality>,
) {
    val aggregate: DataQuality get() = overrides.values.fold(baseline) { acc, quality -> acc.combine(quality) }
    fun at(index: Int): DataQuality = overrides[index] ?: baseline
}

private val DenseDoubleTimeSeriesRow.quality: DenseRowQuality
    get() = DenseRowQuality(baselineQuality, qualityOverrides)

private fun transposeColumns(
    series: List<Name>,
    rows: List<DenseDoubleTimeSeriesRow>,
): List<Float64Buffer> {
    rows.forEach { row ->
        require(row.values.size == series.size) {
            "Dense row has ${row.values.size} values for ${series.size} series."
        }
    }
    return List(series.size) { column -> Float64Buffer(rows.size) { row -> rows[row].values[column] } }
}

public class DenseDoubleTimeSeriesRow(
    public val time: Instant,
    public val values: DoubleArray,
    public val baselineQuality: DataQuality = DataQuality.GOOD,
    public val qualityOverrides: Map<Int, DataQuality> = emptyMap(),
) {
    init {
        require(qualityOverrides.keys.all { it in values.indices }) {
            "Dense quality override index must be inside 0 until ${values.size}."
        }
    }

    public val aggregateQuality: DataQuality
        get() {
            var result = baselineQuality
            for (quality in qualityOverrides.values) result = result.combine(quality)
            return result
        }

    /** Zero-copy typed view of [values] for KMath buffer algebras and unboxed iteration. */
    public val valuesBuffer: Float64Buffer get() = Float64Buffer(values)

    public operator fun get(index: Int): Double = values[index]

    public fun qualityAt(index: Int): DataQuality {
        require(index in values.indices) { "Dense value index must be inside 0 until ${values.size}." }
        return qualityOverrides[index] ?: baselineQuality
    }
}

public interface DenseDoubleTimeSeriesChunkSink {
    public suspend fun append(chunk: DenseDoubleTimeSeriesChunk)
}

public fun TimeSeriesChunk<Double>.toDenseDoubleChunk(default: Double = Double.NaN): DenseDoubleTimeSeriesChunk {
    val indexes = series.withIndex().associate { it.value to it.index }
    val denseRows = rows.map { row ->
        val values = DoubleArray(series.size) { default }
        row.values.forEach { (name, value) ->
            val index = indexes[name]
            if (index != null) values[index] = value
        }
        DenseDoubleTimeSeriesRow(row.time, values, baselineQuality = row.quality)
    }
    return DenseDoubleTimeSeriesChunk(series, denseRows)
}
