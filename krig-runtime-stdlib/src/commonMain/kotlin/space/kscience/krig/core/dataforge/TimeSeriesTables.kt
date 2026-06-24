package space.kscience.krig.core.dataforge

import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeriesChunk
import space.kscience.tables.Column
import space.kscience.tables.ColumnTable
import space.kscience.tables.Table
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Instant

/** Column names shared by KRig time-series table adapters. */
public object TimeSeriesTableColumns {
    public const val EVENT_TIME: String = "eventTime"

    /** Generic (boxed) path: aggregate row quality as a [DataQuality] object. */
    public const val AGGREGATE_QUALITY: String = "aggregateQuality"

    /**
     * Dense path: aggregate row quality as the numeric [space.kscience.krig.api.data.QualitySeverity]
     * rank (zero-alloc — no per-row enum→`String` materialization), mirroring an Arrow `IntVector`.
     */
    public const val AGGREGATE_QUALITY_SEVERITY: String = "aggregateQualitySeverity"
}

/**
 * Column-table view for analytical/export tooling. Quality is carried as a [DataQuality] object on the
 * generic (already boxed) path. Columns are evaluated lazily per cell — no per-row `Map` is built.
 */
public fun <T> TimeSeriesChunk<T>.asTable(): Table<Any?> {
    val materializedRows = rows
    val columns = buildList<Column<Any?>> {
        add(LazyColumn(TimeSeriesTableColumns.EVENT_TIME, typeOf<Instant>(), materializedRows.size) { materializedRows[it].time })
        add(
            LazyColumn(TimeSeriesTableColumns.AGGREGATE_QUALITY, typeOf<DataQuality>(), materializedRows.size) {
                materializedRows[it].quality
            },
        )
        series.forEach { name ->
            add(LazyColumn(name.toString(), typeOf<Any?>(), materializedRows.size) { materializedRows[it].values[name] })
        }
    }
    return ColumnTable(columns)
}

/**
 * Column-table view for dense primitive telemetry. Value columns read straight from the column-major
 * [Float64Buffer][space.kscience.kmath.structures.Float64Buffer] store (zero-copy) and quality is the
 * numeric severity-rank column ([TimeSeriesTableColumns.AGGREGATE_QUALITY_SEVERITY]), preserving the
 * chunk's columnar, allocation-free layout instead of boxing a `DataQuality`/`String` per row.
 */
public fun DenseDoubleTimeSeriesChunk.asTable(): Table<Any?> {
    val columns = buildList<Column<Any?>> {
        add(LazyColumn(TimeSeriesTableColumns.EVENT_TIME, typeOf<Instant>(), rowCount) { times[it] })
        add(
            LazyColumn(TimeSeriesTableColumns.AGGREGATE_QUALITY_SEVERITY, typeOf<Int>(), rowCount) { row ->
                aggregateQualityAt(row).severity.rank
            },
        )
        series.forEachIndexed { index, name ->
            add(LazyColumn(name.toString(), typeOf<Double>(), rowCount) { value(it, index) })
        }
    }
    return ColumnTable(columns)
}

/** Lazily-evaluated [Column] backed by a getter — no intermediate list/map allocation per row. */
private class LazyColumn<T>(
    override val name: String,
    override val type: KType,
    override val size: Int,
    private val getter: (Int) -> T?,
) : Column<T> {
    override val meta: Meta get() = Meta.EMPTY
    override fun getOrNull(index: Int): T? = if (index in 0 until size) getter(index) else null
}
