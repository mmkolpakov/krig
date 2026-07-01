package space.kscience.krig.core.dataforge

import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.DataSource
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.storage.journal.EventJournal
import space.kscience.krig.storage.journal.ReplayRecord
import space.kscience.krig.storage.timeseries.DenseBooleanTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseIntTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseLongTimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeries
import space.kscience.krig.storage.timeseries.TimeSeriesChunk
import space.kscience.krig.storage.timeseries.TimeSeriesSample
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Instant

/**
 * Exposes a [TimeSeries] as a single-node DataForge [DataSource].
 *
 * **Snapshot semantics.** The node's [Data] is lazy but *materializing*: each `read` collects the
 * whole series via `readAll().toList()`, eagerly buffering every [TimeSeriesSample] into a `List` at
 * the moment the consumer awaits the `Data`. This is a point-in-time snapshot — it neither streams
 * nor tracks subsequent appends. Intended for bounded series (export, analysis, tests). For large or
 * unbounded telemetry use chunk adapters ([TimeSeriesChunk.asChunkDataSource],
 * [DenseDoubleTimeSeriesChunk.asChunkDataSource]) or consume the underlying `readAll()` `Flow`
 * directly.
 */
public inline fun <reified T> TimeSeries<T>.asSnapshotDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<List<TimeSeriesSample<T>>> {
    val nodeName = name
    return object : DataSource<List<TimeSeriesSample<T>>> {
        override val dataType: KType = typeOf<List<TimeSeriesSample<T>>>()

        override fun read(name: Name): Data<List<TimeSeriesSample<T>>>? =
            if (name == Name.EMPTY || name == nodeName) {
                Data(meta = meta) { this@asSnapshotDataSource.readAll().toList() }
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
public fun EventJournal.asSnapshotDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<List<DeviceMessageFrame<DeviceMessage>>> {
    val nodeName = name
    return object : DataSource<List<DeviceMessageFrame<DeviceMessage>>> {
        override val dataType: KType = typeOf<List<DeviceMessageFrame<DeviceMessage>>>()

        override fun read(name: Name): Data<List<DeviceMessageFrame<DeviceMessage>>>? =
            if (name == Name.EMPTY || name == nodeName) {
                Data(meta = meta) { this@asSnapshotDataSource.readAll().toList() }
            } else {
                null
            }
    }
}

/**
 * Bounded replay window as one lazy DataForge node. The selected records are materialised only when
 * the node is awaited, and persistent journal backends can override [EventJournal.replayRecords] with
 * an indexed seek instead of scanning the whole log.
 */
public fun EventJournal.asReplayWindowDataSource(
    name: Name,
    from: Instant,
    until: Instant,
    meta: Meta = Meta.EMPTY,
): DataSource<List<ReplayRecord>> {
    val nodeName = name
    return object : DataSource<List<ReplayRecord>> {
        override val dataType: KType = typeOf<List<ReplayRecord>>()

        override fun read(name: Name): Data<List<ReplayRecord>>? =
            if (name == Name.EMPTY || name == nodeName) {
                Data(meta = meta) { this@asReplayWindowDataSource.replayRecords(from, until).toList() }
            } else {
                null
            }
    }
}

/** Exposes one row-oriented chunk as a single lazy DataForge node without flattening rows. */
public inline fun <reified T> TimeSeriesChunk<T>.asChunkDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<TimeSeriesChunk<T>> {
    val nodeName = name
    return object : DataSource<TimeSeriesChunk<T>> {
        override val dataType: KType = typeOf<TimeSeriesChunk<T>>()

        override fun read(name: Name): Data<TimeSeriesChunk<T>>? =
            if (name == Name.EMPTY || name == nodeName) Data(this@asChunkDataSource, meta) else null
    }
}

/**
 * Exposes one dense primitive telemetry chunk as a single DataForge node. This keeps the column-major
 * [DenseDoubleTimeSeriesChunk] intact for Arrow/export reducers instead of turning it into row lists.
 */
public fun DenseDoubleTimeSeriesChunk.asChunkDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<DenseDoubleTimeSeriesChunk> = chunkDataSource(this, name, meta)

public fun DenseIntTimeSeriesChunk.asChunkDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<DenseIntTimeSeriesChunk> = chunkDataSource(this, name, meta)

public fun DenseLongTimeSeriesChunk.asChunkDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<DenseLongTimeSeriesChunk> = chunkDataSource(this, name, meta)

public fun DenseBooleanTimeSeriesChunk.asChunkDataSource(
    name: Name,
    meta: Meta = Meta.EMPTY,
): DataSource<DenseBooleanTimeSeriesChunk> = chunkDataSource(this, name, meta)

private inline fun <reified C : Any> chunkDataSource(
    chunk: C,
    name: Name,
    meta: Meta,
): DataSource<C> {
    val nodeName = name
    return object : DataSource<C> {
        override val dataType: KType = typeOf<C>()

        override fun read(name: Name): Data<C>? =
            if (name == Name.EMPTY || name == nodeName) Data(chunk, meta) else null
    }
}
