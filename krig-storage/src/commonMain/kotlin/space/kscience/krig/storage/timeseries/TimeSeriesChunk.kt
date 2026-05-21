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
