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

/** Common columnar time-series facet shared by primitive dense chunks. */
public interface ColumnarTimeSeriesChunk {
    public val series: List<Name>
    public val times: List<Instant>
    public val rowCount: Int

    public fun severityColumn(seriesIndex: Int): IntArray

    public fun qualityAt(row: Int, seriesIndex: Int): DataQuality

    public fun aggregateQualityAt(row: Int): DataQuality
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
    override val series: List<Name>,
    override val times: List<Instant>,
    private val columns: List<Float64Buffer>,
    private val severityRanks: IntArray,
    private val qualityDetails: Map<Int, DataQuality>,
) : ColumnarTimeSeriesChunk {
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

    override val rowCount: Int get() = times.size

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
    override fun severityColumn(seriesIndex: Int): IntArray {
        require(seriesIndex in series.indices) {
            "Series index must be inside 0 until ${series.size}, got $seriesIndex."
        }
        val base = seriesIndex * rowCount
        return IntArray(rowCount) { severityRanks[base + it] }
    }

    override fun qualityAt(row: Int, seriesIndex: Int): DataQuality {
        val cell = cellIndex(row, seriesIndex)
        qualityDetails[cell]?.let { return it }
        val rank = severityRanks[cell]
        return if (rank == QualitySeverity.GOOD.rank) DataQuality.GOOD else DataQuality(QualitySeverity(rank))
    }

    override fun aggregateQualityAt(row: Int): DataQuality {
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

public class DenseIntTimeSeriesChunk private constructor(
    override val series: List<Name>,
    override val times: List<Instant>,
    private val columns: List<IntArray>,
    private val severityRanks: IntArray,
    private val qualityDetails: Map<Int, DataQuality>,
) : ColumnarTimeSeriesChunk {
    public constructor(
        series: List<Name>,
        rows: List<DenseIntTimeSeriesRow>,
    ) : this(
        series,
        rows.map { it.time },
        transposeIntColumns(series, rows),
        buildPrimitiveSeverityBand(series, rows) { row, index -> row.qualityAt(index) },
        buildPrimitiveQualityDetails(series, rows) { row, index -> row.qualityAt(index) },
    )

    override val rowCount: Int get() = times.size

    public fun column(seriesIndex: Int): IntArray {
        requireSeriesIndex(seriesIndex)
        return columns[seriesIndex]
    }

    public fun value(row: Int, seriesIndex: Int): Int = columns[seriesIndex][row]

    override fun severityColumn(seriesIndex: Int): IntArray = severityColumnFor(seriesIndex, series, rowCount, severityRanks)

    override fun qualityAt(row: Int, seriesIndex: Int): DataQuality =
        qualityAtCell(row, seriesIndex, rowCount, severityRanks, qualityDetails)

    override fun aggregateQualityAt(row: Int): DataQuality = aggregateQualityAtCell(row, series, ::qualityAt)

    public fun row(index: Int): DenseIntTimeSeriesRow = DenseIntTimeSeriesRow(
        time = times[index],
        values = IntArray(series.size) { columns[it][index] },
        baselineQuality = DataQuality.GOOD,
        qualityOverrides = qualityOverridesAt(index, series, ::qualityAt),
    )

    public val rows: List<DenseIntTimeSeriesRow> by lazy { List(rowCount, ::row) }
}

public class DenseLongTimeSeriesChunk private constructor(
    override val series: List<Name>,
    override val times: List<Instant>,
    private val columns: List<LongArray>,
    private val severityRanks: IntArray,
    private val qualityDetails: Map<Int, DataQuality>,
) : ColumnarTimeSeriesChunk {
    public constructor(
        series: List<Name>,
        rows: List<DenseLongTimeSeriesRow>,
    ) : this(
        series,
        rows.map { it.time },
        transposeLongColumns(series, rows),
        buildPrimitiveSeverityBand(series, rows) { row, index -> row.qualityAt(index) },
        buildPrimitiveQualityDetails(series, rows) { row, index -> row.qualityAt(index) },
    )

    override val rowCount: Int get() = times.size

    public fun column(seriesIndex: Int): LongArray {
        requireSeriesIndex(seriesIndex)
        return columns[seriesIndex]
    }

    public fun value(row: Int, seriesIndex: Int): Long = columns[seriesIndex][row]

    override fun severityColumn(seriesIndex: Int): IntArray = severityColumnFor(seriesIndex, series, rowCount, severityRanks)

    override fun qualityAt(row: Int, seriesIndex: Int): DataQuality =
        qualityAtCell(row, seriesIndex, rowCount, severityRanks, qualityDetails)

    override fun aggregateQualityAt(row: Int): DataQuality = aggregateQualityAtCell(row, series, ::qualityAt)

    public fun row(index: Int): DenseLongTimeSeriesRow = DenseLongTimeSeriesRow(
        time = times[index],
        values = LongArray(series.size) { columns[it][index] },
        baselineQuality = DataQuality.GOOD,
        qualityOverrides = qualityOverridesAt(index, series, ::qualityAt),
    )

    public val rows: List<DenseLongTimeSeriesRow> by lazy { List(rowCount, ::row) }
}

public class DenseBooleanTimeSeriesChunk private constructor(
    override val series: List<Name>,
    override val times: List<Instant>,
    private val columns: List<BooleanArray>,
    private val severityRanks: IntArray,
    private val qualityDetails: Map<Int, DataQuality>,
) : ColumnarTimeSeriesChunk {
    public constructor(
        series: List<Name>,
        rows: List<DenseBooleanTimeSeriesRow>,
    ) : this(
        series,
        rows.map { it.time },
        transposeBooleanColumns(series, rows),
        buildPrimitiveSeverityBand(series, rows) { row, index -> row.qualityAt(index) },
        buildPrimitiveQualityDetails(series, rows) { row, index -> row.qualityAt(index) },
    )

    override val rowCount: Int get() = times.size

    public fun column(seriesIndex: Int): BooleanArray {
        requireSeriesIndex(seriesIndex)
        return columns[seriesIndex]
    }

    public fun value(row: Int, seriesIndex: Int): Boolean = columns[seriesIndex][row]

    override fun severityColumn(seriesIndex: Int): IntArray = severityColumnFor(seriesIndex, series, rowCount, severityRanks)

    override fun qualityAt(row: Int, seriesIndex: Int): DataQuality =
        qualityAtCell(row, seriesIndex, rowCount, severityRanks, qualityDetails)

    override fun aggregateQualityAt(row: Int): DataQuality = aggregateQualityAtCell(row, series, ::qualityAt)

    public fun row(index: Int): DenseBooleanTimeSeriesRow = DenseBooleanTimeSeriesRow(
        time = times[index],
        values = BooleanArray(series.size) { columns[it][index] },
        baselineQuality = DataQuality.GOOD,
        qualityOverrides = qualityOverridesAt(index, series, ::qualityAt),
    )

    public val rows: List<DenseBooleanTimeSeriesRow> by lazy { List(rowCount, ::row) }
}

public class DenseIntTimeSeriesRow(
    public val time: Instant,
    public val values: IntArray,
    public val baselineQuality: DataQuality = DataQuality.GOOD,
    public val qualityOverrides: Map<Int, DataQuality> = emptyMap(),
) {
    init {
        require(qualityOverrides.keys.all { it in values.indices }) {
            "Dense quality override index must be inside 0 until ${values.size}."
        }
    }

    public val aggregateQuality: DataQuality get() = aggregateQuality(baselineQuality, qualityOverrides)

    public operator fun get(index: Int): Int = values[index]

    public fun qualityAt(index: Int): DataQuality {
        require(index in values.indices) { "Dense value index must be inside 0 until ${values.size}." }
        return qualityOverrides[index] ?: baselineQuality
    }
}

public class DenseLongTimeSeriesRow(
    public val time: Instant,
    public val values: LongArray,
    public val baselineQuality: DataQuality = DataQuality.GOOD,
    public val qualityOverrides: Map<Int, DataQuality> = emptyMap(),
) {
    init {
        require(qualityOverrides.keys.all { it in values.indices }) {
            "Dense quality override index must be inside 0 until ${values.size}."
        }
    }

    public val aggregateQuality: DataQuality get() = aggregateQuality(baselineQuality, qualityOverrides)

    public operator fun get(index: Int): Long = values[index]

    public fun qualityAt(index: Int): DataQuality {
        require(index in values.indices) { "Dense value index must be inside 0 until ${values.size}." }
        return qualityOverrides[index] ?: baselineQuality
    }
}

public class DenseBooleanTimeSeriesRow(
    public val time: Instant,
    public val values: BooleanArray,
    public val baselineQuality: DataQuality = DataQuality.GOOD,
    public val qualityOverrides: Map<Int, DataQuality> = emptyMap(),
) {
    init {
        require(qualityOverrides.keys.all { it in values.indices }) {
            "Dense quality override index must be inside 0 until ${values.size}."
        }
    }

    public val aggregateQuality: DataQuality get() = aggregateQuality(baselineQuality, qualityOverrides)

    public operator fun get(index: Int): Boolean = values[index]

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

public fun TimeSeriesChunk<Int>.toDenseIntChunk(default: Int = 0): DenseIntTimeSeriesChunk {
    val indexes = series.withIndex().associate { it.value to it.index }
    val denseRows = rows.map { row ->
        val values = IntArray(series.size) { default }
        row.values.forEach { (name, value) ->
            val index = requireNotNull(indexes[name]) {
                "Row at ${row.time} carries series '$name' that is not declared in the chunk series list."
            }
            values[index] = value
        }
        DenseIntTimeSeriesRow(row.time, values, baselineQuality = row.quality)
    }
    return DenseIntTimeSeriesChunk(series, denseRows)
}

public fun TimeSeriesChunk<Long>.toDenseLongChunk(default: Long = 0L): DenseLongTimeSeriesChunk {
    val indexes = series.withIndex().associate { it.value to it.index }
    val denseRows = rows.map { row ->
        val values = LongArray(series.size) { default }
        row.values.forEach { (name, value) ->
            val index = requireNotNull(indexes[name]) {
                "Row at ${row.time} carries series '$name' that is not declared in the chunk series list."
            }
            values[index] = value
        }
        DenseLongTimeSeriesRow(row.time, values, baselineQuality = row.quality)
    }
    return DenseLongTimeSeriesChunk(series, denseRows)
}

public fun TimeSeriesChunk<Boolean>.toDenseBooleanChunk(default: Boolean = false): DenseBooleanTimeSeriesChunk {
    val indexes = series.withIndex().associate { it.value to it.index }
    val denseRows = rows.map { row ->
        val values = BooleanArray(series.size) { default }
        row.values.forEach { (name, value) ->
            val index = requireNotNull(indexes[name]) {
                "Row at ${row.time} carries series '$name' that is not declared in the chunk series list."
            }
            values[index] = value
        }
        DenseBooleanTimeSeriesRow(row.time, values, baselineQuality = row.quality)
    }
    return DenseBooleanTimeSeriesChunk(series, denseRows)
}

private fun aggregateQuality(baseline: DataQuality, overrides: Map<Int, DataQuality>): DataQuality {
    var result = baseline
    for (quality in overrides.values) result = result.combine(quality)
    return result
}

private inline fun <R> buildPrimitiveSeverityBand(
    series: List<Name>,
    rows: List<R>,
    qualityAt: (R, Int) -> DataQuality,
): IntArray {
    val band = IntArray(series.size * rows.size)
    for (seriesIndex in series.indices) {
        val base = seriesIndex * rows.size
        for (row in rows.indices) band[base + row] = qualityAt(rows[row], seriesIndex).severity.rank
    }
    return band
}

private inline fun <R> buildPrimitiveQualityDetails(
    series: List<Name>,
    rows: List<R>,
    qualityAt: (R, Int) -> DataQuality,
): Map<Int, DataQuality> {
    val details = HashMap<Int, DataQuality>()
    for (seriesIndex in series.indices) {
        val base = seriesIndex * rows.size
        for (row in rows.indices) {
            val quality = qualityAt(rows[row], seriesIndex)
            if (quality.code != null || quality.detail != null) details[base + row] = quality
        }
    }
    return details
}

private fun severityColumnFor(
    seriesIndex: Int,
    series: List<Name>,
    rowCount: Int,
    severityRanks: IntArray,
): IntArray {
    require(seriesIndex in series.indices) {
        "Series index must be inside 0 until ${series.size}, got $seriesIndex."
    }
    val base = seriesIndex * rowCount
    return IntArray(rowCount) { severityRanks[base + it] }
}

private fun qualityAtCell(
    row: Int,
    seriesIndex: Int,
    rowCount: Int,
    severityRanks: IntArray,
    qualityDetails: Map<Int, DataQuality>,
): DataQuality {
    val cell = seriesIndex * rowCount + row
    qualityDetails[cell]?.let { return it }
    val rank = severityRanks[cell]
    return if (rank == QualitySeverity.GOOD.rank) DataQuality.GOOD else DataQuality(QualitySeverity(rank))
}

private inline fun aggregateQualityAtCell(
    row: Int,
    series: List<Name>,
    qualityAt: (Int, Int) -> DataQuality,
): DataQuality {
    var result = DataQuality.GOOD
    for (seriesIndex in series.indices) result = result.combine(qualityAt(row, seriesIndex))
    return result
}

private inline fun qualityOverridesAt(
    row: Int,
    series: List<Name>,
    qualityAt: (Int, Int) -> DataQuality,
): Map<Int, DataQuality> {
    val overrides = HashMap<Int, DataQuality>()
    for (seriesIndex in series.indices) {
        val quality = qualityAt(row, seriesIndex)
        if (quality != DataQuality.GOOD) overrides[seriesIndex] = quality
    }
    return overrides
}

private fun DenseIntTimeSeriesChunk.requireSeriesIndex(seriesIndex: Int) {
    require(seriesIndex in series.indices) {
        "Series index must be inside 0 until ${series.size}, got $seriesIndex."
    }
}

private fun DenseLongTimeSeriesChunk.requireSeriesIndex(seriesIndex: Int) {
    require(seriesIndex in series.indices) {
        "Series index must be inside 0 until ${series.size}, got $seriesIndex."
    }
}

private fun DenseBooleanTimeSeriesChunk.requireSeriesIndex(seriesIndex: Int) {
    require(seriesIndex in series.indices) {
        "Series index must be inside 0 until ${series.size}, got $seriesIndex."
    }
}

private fun transposeIntColumns(series: List<Name>, rows: List<DenseIntTimeSeriesRow>): List<IntArray> {
    rows.forEach { row ->
        require(row.values.size == series.size) {
            "Dense row has ${row.values.size} values for ${series.size} series."
        }
    }
    return List(series.size) { column -> IntArray(rows.size) { row -> rows[row].values[column] } }
}

private fun transposeLongColumns(series: List<Name>, rows: List<DenseLongTimeSeriesRow>): List<LongArray> {
    rows.forEach { row ->
        require(row.values.size == series.size) {
            "Dense row has ${row.values.size} values for ${series.size} series."
        }
    }
    return List(series.size) { column -> LongArray(rows.size) { row -> rows[row].values[column] } }
}

private fun transposeBooleanColumns(series: List<Name>, rows: List<DenseBooleanTimeSeriesRow>): List<BooleanArray> {
    rows.forEach { row ->
        require(row.values.size == series.size) {
            "Dense row has ${row.values.size} values for ${series.size} series."
        }
    }
    return List(series.size) { column -> BooleanArray(rows.size) { row -> rows[row].values[column] } }
}
