package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import kotlin.time.Instant

/** Typed time-series point. `GOOD` quality may be omitted by compact storage profiles. */
public data class TimeSeriesSample<out T>(
    public val series: Name,
    public val value: T,
    public val time: Instant,
    public val quality: DataQuality = DataQuality.GOOD,
) {
    public val observed: ObservedValue<T> get() = ObservedValue(value, time, quality)
}

public fun <T> timeSeriesSample(series: Name, observed: ObservedValue<T>): TimeSeriesSample<T> =
    TimeSeriesSample(series = series, value = observed.value, time = observed.time, quality = observed.quality)
