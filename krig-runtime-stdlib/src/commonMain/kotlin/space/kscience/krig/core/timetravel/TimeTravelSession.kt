@file:MustUseReturnValues

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.storage.journal.CursorReplayLog
import space.kscience.krig.storage.journal.ReplayLog
import kotlin.time.Instant

/**
 * Replay controller bound to one [model], its [log], and the [snapshotStore] that checkpoints it.
 * The methods mirror the free replay functions but drop the repeated `(log, store, name, codec)`
 * threading. Obtain one from [withTimeTravel].
 */
@ExperimentalTimeTravelApi
public class TimeTravelSession internal constructor(
    public val model: Reconstructible,
    private val log: ReplayLog,
    private val snapshotStore: SnapshotStore,
    private val deviceName: Name,
    private val snapshotCodec: SnapshotCodec,
    /**
     * Handle of the live recording wiring when this session was created by `withTimeTravel`;
     * cancel it to stop recording without tearing down the device scope. `null` for sessions
     * over an already-recorded log.
     */
    public val recording: Job? = null,
) {
    /** Reconstructs model state as of [at], resuming from the nearest stored snapshot. */
    @IgnorableReturnValue
    public suspend fun seek(at: Instant): TimeTravelSession {
        model.timeTravel(at, log, deviceName, snapshotStore, snapshotCodec)
        return this
    }

    /**
     * Replays up to [until] (over [snapshot] when given), stopping once [predicate] matches.
     * Returns the matched event, or `null` if none matched before [until].
     */
    public suspend fun replayUntil(
        until: Instant = Instant.DISTANT_FUTURE,
        snapshot: DeviceSnapshot? = null,
        predicate: (DeviceMessage) -> Boolean,
    ): DeviceMessage? = model.replayUntil(log, snapshot, until, predicate)

    /** Replays to [at] (over [snapshot] when given), applying [mutator] to each event. */
    @IgnorableReturnValue
    public suspend fun counterfactual(
        at: Instant,
        snapshot: DeviceSnapshot? = null,
        mutator: (DeviceMessage) -> DeviceMessage,
    ): TimeTravelSession {
        model.counterfactual(log, at, snapshot, mutator)
        return this
    }

    /**
     * Captures a deterministic [BranchPoint] at [at]. The session [log] must be a
     * [CursorReplayLog]; sessions from [withTimeTravel] always satisfy this.
     */
    public suspend fun branchAt(at: Instant, from: Instant = Instant.DISTANT_PAST): BranchPoint {
        val cursorLog = log as? CursorReplayLog
            ?: error("branchAt requires a CursorReplayLog; this session is bound to a timestamp-only ReplayLog.")
        return model.branchAt(cursorLog, at, from)
    }

    /** Reverts to [branch]'s state, then applies [alternativeTimeline] up to [horizon]. */
    @IgnorableReturnValue
    public suspend fun whatIf(
        branch: BranchPoint,
        alternativeTimeline: Flow<DeviceMessage>,
        horizon: Instant = Instant.DISTANT_FUTURE,
    ): TimeTravelSession {
        model.whatIf(branch, alternativeTimeline, horizon)
        return this
    }
}
