package space.kscience.krig.core.dataforge

import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.DataSource
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.storage.journal.EventJournal
import space.kscience.krig.storage.timeseries.TimeSeries
import space.kscience.krig.storage.timeseries.TimeSeriesSample
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Exposes a [TimeSeries] as a single-node DataForge [DataSource].
 *
 * **Snapshot semantics.** The node's [Data] is lazy but *materializing*: each `read` collects the
 * whole series via `readAll().toList()`, eagerly buffering every [TimeSeriesSample] into a `List` at
 * the moment the consumer awaits the `Data`. This is a point-in-time snapshot — it neither streams
 * nor tracks subsequent appends. Intended for bounded series (export, analysis, tests); for large or
 * unbounded telemetry consume the underlying `readAll()` `Flow` directly instead of this adapter.
 */
public inline fun <reified T> TimeSeries<T>.asDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<List<TimeSeriesSample<T>>> {
    val nodeName = name
    return object : DataSource<List<TimeSeriesSample<T>>> {
        override val dataType: KType = typeOf<List<TimeSeriesSample<T>>>()

        override fun read(name: Name): Data<List<TimeSeriesSample<T>>>? =
            if (name == Name.EMPTY || name == nodeName) {
                Data(meta = meta) { this@asDataSource.readAll().toList() }
            } else {
                null
            }
    }
}

/**
 * Exposes an [EventJournal] as a single-node DataForge [DataSource].
 *
 * **Snapshot semantics.** Same as the [TimeSeries] overload: each `read` materializes the journal via
 * `readAll().toList()`, buffering all [DeviceMessageFrame]s into a `List` when the `Data` is awaited.
 * It is a point-in-time snapshot, not a live tail — for large journals consume `readAll()` directly.
 */
public fun EventJournal.asDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<List<DeviceMessageFrame<DeviceMessage>>> {
    val nodeName = name
    return object : DataSource<List<DeviceMessageFrame<DeviceMessage>>> {
        override val dataType: KType = typeOf<List<DeviceMessageFrame<DeviceMessage>>>()

        override fun read(name: Name): Data<List<DeviceMessageFrame<DeviceMessage>>>? =
            if (name == Name.EMPTY || name == nodeName) {
                Data(meta = meta) { this@asDataSource.readAll().toList() }
            } else {
                null
            }
    }
}
