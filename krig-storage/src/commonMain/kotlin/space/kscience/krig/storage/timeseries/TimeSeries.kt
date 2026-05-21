package space.kscience.krig.storage.timeseries

import kotlinx.coroutines.flow.Flow

/** Append-only typed series. Implementations choose DB, file, broker or chunk store. */
public interface TimeSeries<T> {
    public suspend fun append(sample: TimeSeriesSample<T>)

    public fun readAll(): Flow<TimeSeriesSample<T>>
}
