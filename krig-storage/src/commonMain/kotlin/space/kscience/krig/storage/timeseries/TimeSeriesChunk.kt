package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.Name
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

/** Dense primitive chunk for high-frequency double telemetry. */
public class DenseDoubleTimeSeriesChunk(
    public val series: List<Name>,
    public val rows: List<DenseDoubleTimeSeriesRow>,
) {
    init {
        rows.forEach { row ->
            require(row.values.size == series.size) {
                "Dense row has ${row.values.size} values for ${series.size} series."
            }
        }
    }
}

public class DenseDoubleTimeSeriesRow(
    public val time: Instant,
    public val values: DoubleArray,
    public val quality: DataQuality = DataQuality.GOOD,
) {
    public operator fun get(index: Int): Double = values[index]
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
        DenseDoubleTimeSeriesRow(row.time, values, row.quality)
    }
    return DenseDoubleTimeSeriesChunk(series, denseRows)
}
