package space.kscience.krig.core.dataforge

import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeriesChunk
import space.kscience.tables.ColumnHeader
import space.kscience.tables.MapRow
import space.kscience.tables.RowTable
import space.kscience.tables.Table

/** Column names shared by KRig time-series table adapters. */
public object TimeSeriesTableColumns {
    public const val EVENT_TIME: String = "eventTime"
    public const val AGGREGATE_QUALITY: String = "aggregateQuality"
}

/** Row-table view for analytical/export tooling. */
public fun <T> TimeSeriesChunk<T>.asTable(): Table<Any?> {
    val valueColumns = series.map { it.toString() }
    val tableRows = rows.map { row ->
        telemetryRow {
            put(TimeSeriesTableColumns.EVENT_TIME, row.time)
            put(TimeSeriesTableColumns.AGGREGATE_QUALITY, row.quality)
            series.forEachIndexed { index, name ->
                put(valueColumns[index], row.values[name])
            }
        }
    }
    return telemetryTable(valueColumns, tableRows)
}

/** Row-table view for dense primitive telemetry. */
public fun DenseDoubleTimeSeriesChunk.asTable(): Table<Any?> {
    val valueColumns = series.map { it.toString() }
    val tableRows = rows.map { row ->
        telemetryRow {
            put(TimeSeriesTableColumns.EVENT_TIME, row.time)
            put(TimeSeriesTableColumns.AGGREGATE_QUALITY, row.aggregateQuality)
            valueColumns.forEachIndexed { index, column ->
                put(column, row.values[index])
            }
        }
    }
    return telemetryTable(valueColumns, tableRows)
}

private fun telemetryTable(valueColumns: List<String>, rows: List<MapRow<Any?>>): Table<Any?> {
    val headers = listOf(
        ColumnHeader<Any?>(TimeSeriesTableColumns.EVENT_TIME),
        ColumnHeader<Any?>(TimeSeriesTableColumns.AGGREGATE_QUALITY),
    ) + valueColumns.map { ColumnHeader<Any?>(it) }
    return RowTable(headers, rows)
}

private fun telemetryRow(builder: MutableMap<String, Any?>.() -> Unit): MapRow<Any?> =
    MapRow(buildMap(builder))
