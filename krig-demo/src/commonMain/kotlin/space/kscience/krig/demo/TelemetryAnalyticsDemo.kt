package space.kscience.krig.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import space.kscience.dataforge.data.await
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.assembly.ReductionSpec
import space.kscience.krig.assembly.reduceToBins
import space.kscience.krig.assembly.resample
import space.kscience.krig.assembly.totalize
import space.kscience.krig.core.dataforge.TimeSeriesTableColumns
import space.kscience.krig.core.dataforge.asDataSource
import space.kscience.krig.core.dataforge.asTable
import space.kscience.krig.storage.timeseries.RowsCompression
import space.kscience.krig.storage.timeseries.TimeSeries
import space.kscience.krig.storage.timeseries.TimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeriesRow
import space.kscience.krig.storage.timeseries.TimeSeriesSample
import space.kscience.krig.storage.timeseries.compressRows
import space.kscience.krig.storage.timeseries.mean
import space.kscience.krig.storage.timeseries.toDenseDoubleChunk
import space.kscience.tables.Table
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Time-series compression plus DataForge table/source adapters for notebook analytics. */
suspend fun telemetryAnalyticsDemo() {
    val raw = pumpTelemetryChunk()
    val compressed = raw.compressRows(RowsCompression(minIntervalMillis = 10))
    val dense = compressed.toDenseDoubleChunk()
    val binned = dense.reduceToBins(20.milliseconds, ReductionSpec.MinMaxMean)
    val regularGrid = dense.resample(10.milliseconds)
    val motorCharge = dense.totalize(motorCurrent)
    val table = compressed.asTable()
    val dataSource = compressed.toSampleSeries().asDataSource(
        name = "passport.telemetry".parseAsName(),
        meta = Meta {
            "scenario" put "predictive-maintenance"
            "asset" put "pump-stand"
        },
    )
    val dataSourceSnapshot = dataSource.read(Name.EMPTY)?.await().orEmpty()
    val diagnosticRows = compressed.rows.filter { row ->
        row.quality.severity >= QualitySeverity.UNCERTAIN ||
            (row.values[vibrationRms] ?: 0.0) >= 0.55 ||
            (row.values[motorCurrent] ?: 0.0) >= 16.0
    }

    println("=== Telemetry analytics ===")
    println("  raw rows -> compressed rows: ${raw.rows.size} -> ${compressed.rows.size}")
    println("  table columns: ${table.headers.map { it.name }}")
    println("  first table row: ${table.firstRowSnapshot()}")
    println("  DataSource samples: ${dataSourceSnapshot.size}")
    println("  average GOOD vibration: ${compressed.averageGoodVibration()}")
    println("  20ms min/max/mean bins: ${binned.rowCount} rows over ${binned.series.size} columns")
    println("  resampled to uniform 10ms grid: ${regularGrid.rowCount} rows (linear interpolation)")
    println("  motor charge over window: $motorCharge A·s (trapezoid ∫ current dt)")
    println(
        "  diagnostic rows: ${
            diagnosticRows.map { row ->
                "${row.time.toEpochMilliseconds()}ms:${row.quality.shortLabel}:vib=${row.values[vibrationRms]}"
            }
        }",
    )
    println("\nDone - telemetry analytics demo complete.")
}

private val rpmSeries: Name = "pump.rpm".parseAsName()
private val temperatureSeries: Name = "pump.temperature".parseAsName()
private val vibrationRms: Name = "pump.vibrationRms".parseAsName()
private val motorCurrent: Name = "pump.motorCurrent".parseAsName()

private fun pumpTelemetryChunk(): TimeSeriesChunk<Double> {
    val bad = DataQuality(QualitySeverity.BAD, detail = "bearing vibration above alarm threshold")
    val uncertain = DataQuality(QualitySeverity.UNCERTAIN, detail = "sensor reports degraded confidence")
    return TimeSeriesChunk(
        series = listOf(rpmSeries, temperatureSeries, vibrationRms, motorCurrent),
        rows = listOf(
            pumpRow(ms = 0, rpm = 920.0, temperature = 61.0, vibration = 0.16, current = 9.8),
            pumpRow(ms = 5, rpm = 920.0, temperature = 61.0, vibration = 0.42, current = 10.4),
            pumpRow(ms = 10, rpm = 920.0, temperature = 61.0, vibration = 0.42, current = 10.4),
            pumpRow(ms = 20, rpm = 910.0, temperature = 66.0, vibration = 0.48, current = 11.2, quality = uncertain),
            pumpRow(ms = 30, rpm = 650.0, temperature = 82.0, vibration = 0.91, current = 18.4, quality = bad),
        ),
    )
}

private fun pumpRow(
    ms: Long,
    rpm: Double,
    temperature: Double,
    vibration: Double,
    current: Double,
    quality: DataQuality = DataQuality.GOOD,
): TimeSeriesRow<Double> = TimeSeriesRow(
    time = Instant.fromEpochMilliseconds(ms),
    values = mapOf(
        rpmSeries to rpm,
        temperatureSeries to temperature,
        vibrationRms to vibration,
        motorCurrent to current,
    ),
    quality = quality,
)

private fun TimeSeriesChunk<Double>.toSampleSeries(): TimeSeries<Double> {
    val samples = rows.flatMap { row ->
        series.mapNotNull { name ->
            row.values[name]?.let { value ->
                TimeSeriesSample(name, value, row.time, row.quality)
            }
        }
    }
    return ListTimeSeries(samples)
}

private class ListTimeSeries<T>(
    initial: List<TimeSeriesSample<T>>,
) : TimeSeries<T> {
    private val samples: MutableList<TimeSeriesSample<T>> = initial.toMutableList()

    override suspend fun append(sample: TimeSeriesSample<T>) {
        samples += sample
    }

    override fun readAll(): Flow<TimeSeriesSample<T>> = samples.asFlow()
}

private fun TimeSeriesChunk<Double>.averageGoodVibration(): Double {
    val good = rows.mapNotNull { row ->
        if (row.quality.severity == QualitySeverity.GOOD) row.values[vibrationRms] else null
    }
    return Float64Buffer(good.toDoubleArray()).mean()
}

private fun Table<Any?>.firstRowSnapshot(): Map<String, Any?> =
    headers.associate { header ->
        header.name to getOrNull(0, header.name)
    }.filterKeys { column ->
        column != TimeSeriesTableColumns.AGGREGATE_QUALITY || getOrNull(0, column) != DataQuality.GOOD
    }
