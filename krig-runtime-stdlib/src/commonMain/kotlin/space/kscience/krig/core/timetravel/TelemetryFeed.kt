package space.kscience.krig.core.timetravel

import space.kscience.dataforge.names.Name
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import kotlin.time.Instant

/**
 * Bulk historical telemetry as dense columns addressed by series [Name] — **not** a per-event
 * stream. This is the Data-Plane counterpart to the event journal: high-frequency captures
 * (vibration, current, position) are consumed as arrays, in column order, so a reconstruction is
 * O(rows) array reads rather than O(events) [Reconstructible.applyEvent] folds.
 *
 * Event sourcing fits the Control Plane (commands, setpoints, status changes — rare, causal), but
 * folding a billion telemetry samples through `applyEvent` does not. A model that needs historical
 * telemetry takes it as a [TelemetryFeed] and integrates its physics over the array directly
 * (see [ContinuousReconstructible.integrateTo]).
 */
public interface TelemetryFeed {
    /** Column identities, positionally aligned with [valueAt]'s `column`. */
    public val series: List<Name>

    /** Number of rows (samples) in the feed. */
    public val rowCount: Int

    /** Event time of row [row]. */
    public fun timeAt(row: Int): Instant

    /** Dense value at [row]/[column]; `NaN` encodes an absent sample (the dense-chunk sentinel). */
    public fun valueAt(row: Int, column: Int): Double
}

/**
 * Exposes a dense double time-series chunk as a [TelemetryFeed] without copying — the columnar store
 * stays the zero-allocation sink, and this is the read-only Data-Plane view over it.
 */
public fun DenseDoubleTimeSeriesChunk.asTelemetryFeed(): TelemetryFeed = object : TelemetryFeed {
    override val series: List<Name> get() = this@asTelemetryFeed.series
    override val rowCount: Int get() = this@asTelemetryFeed.rowCount
    override fun timeAt(row: Int): Instant = times[row]
    override fun valueAt(row: Int, column: Int): Double = value(row, column)
}

/**
 * A [Reconstructible] that can additionally ingest dense historical telemetry as arrays (the Data
 * Plane), bypassing the per-event [applyEvent] fold. [applyEvent] stays the Control-Plane path for
 * commands/setpoints/status; [integrateTo] is the high-frequency path that feeds a [TelemetryFeed]
 * straight into the model's physics. Implementations decide how columns map onto state.
 *
 * This is the contractual split behind KRig's time-travel: a `CommandJournal` is folded event by
 * event, while a `TelemetryFeed` is integrated in bulk — so reconstructing a 10 kHz signal over a day
 * does not replay a billion events.
 */
public interface ContinuousReconstructible<D : Device> : DeviceReconstructible<D> {
    /**
     * Advances model state up to [at] by integrating the dense [feed] directly (no per-sample
     * [applyEvent]). Must be deterministic for reproducible replay.
     */
    public suspend fun integrateTo(at: Instant, feed: TelemetryFeed)
}
