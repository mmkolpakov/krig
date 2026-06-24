package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flow
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.TimeSeriesRowMessage
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk

/**
 * Lifts a dense double time-series chunk onto a [Timeline], emitting one [TimeSeriesRowMessage] per
 * row stamped with [sourceDevice]. This bridges the columnar capture path (the zero-allocation write
 * sink) into the event/replay plane so a chunk can be interleaved with a live event stream through
 * [Timeline.merge] — e.g. to reconstruct a digital twin from both ad-hoc events and bulk captures.
 *
 * Rows carry no HLC stamp (the column store does not record per-cell causality), so [Timeline.merge]
 * orders these frames by payload [TimeSeriesRowMessage.time]; pair with HLC-stamped event timelines
 * accordingly.
 */
public fun DenseDoubleTimeSeriesChunk.asTimeline(sourceDevice: Name): Timeline = Timeline(
    flow {
        for (rowIndex in 0 until rowCount) {
            val message = TimeSeriesRowMessage(
                time = times[rowIndex],
                series = series,
                values = List(series.size) { value(rowIndex, it) },
                sourceDevice = sourceDevice,
                qualities = List(series.size) { qualityAt(rowIndex, it) },
            )
            emit(DeviceMessageFrame(message))
        }
    },
)
