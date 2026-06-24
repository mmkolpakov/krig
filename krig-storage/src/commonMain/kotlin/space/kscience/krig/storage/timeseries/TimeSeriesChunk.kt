package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.Name
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
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
 * Dense primitive chunk for high-frequency double telemetry. Storage is fully column-major: each
 * series is a contiguous [Float64Buffer] of values **and** a parallel column-major primitive band of
 * [QualitySeverity] ranks ([severityRanks]); the full [DataQuality] (string code/detail) is kept in a
 * sparse side map ([qualityDetails]) only for cells that carry one. This mirrors the Arrow export
 * layout (severity = `IntVector`, code/detail = `VarCharVector`) and removes the per-row boxed
 * `Map<Int, DataQuality>` from the hot read path. Build it from rows for convenience; read it through
 * [column]/[value] on hot paths and [rows] for row-shaped tooling.
 */
public class DenseDoubleTimeSeriesChunk private constructor(
    public val series: List<Name>,
    public val times: List<Instant>,
    private val columns: List<Float64Buffer>,
    private val severityRanks: IntArray,
    private val qualityDetails: Map<Int, DataQuality>,
) {
    public constructor(
        series: List<Name>,
        rows: List<DenseDoubleTimeSeriesRow>,
    ) : this(
        series,
        rows.map { it.time },
        transposeColumns(series, rows),
        buildSeverityBand(series, rows),
        buildQualityDetails(series, rows),
    )

    public val rowCount: Int get() = times.size

    /** Column-major cell offset into [severityRanks] / [qualityDetails]: `seriesIndex * rowCount + row`. */
    private fun cellIndex(row: Int, seriesIndex: Int): Int = seriesIndex * rowCount + row

    /** Values of [seriesIndex] across all rows as a contiguous unboxed buffer for KMath algebras. */
    public fun column(seriesIndex: Int): Float64Buffer {
        require(seriesIndex in series.indices) {
            "Series index must be inside 0 until ${series.size}, got $seriesIndex."
        }
        return columns[seriesIndex]
    }

    public fun value(row: Int, seriesIndex: Int): Double = columns[seriesIndex][row]

    /**
     * Severity ranks of [seriesIndex] across all rows as a contiguous unboxed band — the columnar
     * quality counterpart to [column], ready for an Arrow `IntVector` without per-row boxing.
     */
    public fun severityColumn(seriesIndex: Int): IntArray {
        require(seriesIndex in series.indices) {
            "Series index must be inside 0 until ${series.size}, got $seriesIndex."
        }
        val base = seriesIndex * rowCount
        return IntArray(rowCount) { severityRanks[base + it] }
    }

    public fun qualityAt(row: Int, seriesIndex: Int): DataQuality {
        val cell = cellIndex(row, seriesIndex)
        qualityDetails[cell]?.let { return it }
        val rank = severityRanks[cell]
        return if (rank == QualitySeverity.GOOD.rank) DataQuality.GOOD else DataQuality(QualitySeverity(rank))
    }

    public fun aggregateQualityAt(row: Int): DataQuality {
        var result = DataQuality.GOOD
        for (seriesIndex in series.indices) result = result.combine(qualityAt(row, seriesIndex))
        return result
    }

    /** Reconstructs row [index] from the column store; allocates one value array per call. */
    public fun row(index: Int): DenseDoubleTimeSeriesRow {
        val overrides = HashMap<Int, DataQuality>()
        for (seriesIndex in series.indices) {
            val quality = qualityAt(index, seriesIndex)
            if (quality != DataQuality.GOOD) overrides[seriesIndex] = quality
        }
        return DenseDoubleTimeSeriesRow(
            time = times[index],
            values = DoubleArray(series.size) { columns[it][index] },
            baselineQuality = DataQuality.GOOD,
            qualityOverrides = overrides,
        )
    }

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
        // Dense severity band: unavoidable O(series×count) integer gather along the kept rows.
        val selectedBand = IntArray(series.size * count)
        for (seriesIndex in series.indices) {
            val oldBase = seriesIndex * rowCount
            val newBase = seriesIndex * count
            for (newRow in 0 until count) {
                selectedBand[newBase + newRow] = severityRanks[oldBase + keptRows[newRow]]
            }
        }
        // Sparse details: iterate the few carried entries instead of probing every cell. keptRows is
        // ascending by construction, so each old row maps to a new row by binary search — the hash
        // work drops from O(series×rows) gets to O(details·log rows).
        val selectedDetails = HashMap<Int, DataQuality>()
        for ((oldCell, quality) in qualityDetails) {
            val seriesIndex = oldCell / rowCount
            val oldRow = oldCell % rowCount
            val newRow = keptRows.indexOfSortedValue(oldRow, count)
            if (newRow >= 0) selectedDetails[seriesIndex * count + newRow] = quality
        }
        return DenseDoubleTimeSeriesChunk(series, selectedTimes, selectedColumns, selectedBand, selectedDetails)
    }
}

/** Binary search for [value] in the ascending prefix `[0, count)` of this array; `-1` if absent. */
private fun IntArray.indexOfSortedValue(value: Int, count: Int): Int {
    var low = 0
    var high = count - 1
    while (low <= high) {
        val mid = low + high ushr 1
        val midValue = this[mid]
        when {
            midValue < value -> low = mid + 1
            midValue > value -> high = mid - 1
            else -> return mid
        }
    }
    return -1
}

/** Column-major severity band: `severityRanks[seriesIndex * rowCount + row]`. */
private fun buildSeverityBand(series: List<Name>, rows: List<DenseDoubleTimeSeriesRow>): IntArray {
    val rowCount = rows.size
    val band = IntArray(series.size * rowCount)
    for (seriesIndex in series.indices) {
        val base = seriesIndex * rowCount
        for (row in rows.indices) band[base + row] = rows[row].qualityAt(seriesIndex).severity.rank
    }
    return band
}

/** Sparse side map for cells whose quality carries a code/detail that a rank alone cannot reconstruct. */
private fun buildQualityDetails(series: List<Name>, rows: List<DenseDoubleTimeSeriesRow>): Map<Int, DataQuality> {
    val rowCount = rows.size
    val details = HashMap<Int, DataQuality>()
    for (seriesIndex in series.indices) {
        val base = seriesIndex * rowCount
        for (row in rows.indices) {
            val quality = rows[row].qualityAt(seriesIndex)
            if (quality.code != null || quality.detail != null) details[base + row] = quality
        }
    }
    return details
}

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

/**
 * One dense row of double telemetry.
 *
 * Ownership: [values] is taken over by the row without copying (hot-path constraint) — the caller
 * must not mutate the array afterwards. A chunk built from rows snapshots derived data (severity
 * band, columns) at construction, so later external mutation would silently desynchronise them.
 */
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

/**
 * Projects sparse rows onto the chunk's declared [TimeSeriesChunk.series]; missing cells take
 * [default]. A row value whose name is not declared in `series` fails fast — silently dropping it
 * would lose data on a series-list/rows mismatch.
 */
public fun TimeSeriesChunk<Double>.toDenseDoubleChunk(default: Double = Double.NaN): DenseDoubleTimeSeriesChunk {
    val indexes = series.withIndex().associate { it.value to it.index }
    val denseRows = rows.map { row ->
        val values = DoubleArray(series.size) { default }
        row.values.forEach { (name, value) ->
            val index = requireNotNull(indexes[name]) {
                "Row at ${row.time} carries series '$name' that is not declared in the chunk series list."
            }
            values[index] = value
        }
        DenseDoubleTimeSeriesRow(row.time, values, baselineQuality = row.quality)
    }
    return DenseDoubleTimeSeriesChunk(series, denseRows)
}
