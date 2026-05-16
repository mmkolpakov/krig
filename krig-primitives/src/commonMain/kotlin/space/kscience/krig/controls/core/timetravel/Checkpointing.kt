package space.kscience.krig.core.timetravel

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * When to capture a [DeviceSnapshot]. Sealed vocabulary — integrations add their own
 * strategy by composing these, not by subclassing.
 */
public sealed interface CheckpointStrategy {
    /** Capture after every [n] recorded events. */
    public data class EveryNEvents(public val n: Int) : CheckpointStrategy {
        init { require(n > 0) { "CheckpointStrategy.EveryNEvents requires n > 0, got $n" } }
    }

    /** Capture on a wall-clock cadence of [duration]. */
    public data class EveryDuration(public val duration: Duration) : CheckpointStrategy {
        init { require(duration.isPositive()) { "CheckpointStrategy.EveryDuration requires a positive duration" } }
    }

    /** Caller-driven — no automatic capture. */
    public data object Manual : CheckpointStrategy
}

/**
 * Drive snapshotting for a [Reconstructible] tied to the [messageFlow] it folds.
 *
 * The returned [Job] owns the capture loop; cancelling the scope (or the job) stops
 * checkpointing. For [CheckpointStrategy.EveryNEvents], snapshots fire after every `n`
 * events observed on [messageFlow]. For [EveryDuration][CheckpointStrategy.EveryDuration],
 * snapshots are checked on a wall-clock cadence and saved only when their content changes.
 * [Manual][CheckpointStrategy.Manual] returns a completed no-op job.
 */
public fun Reconstructible.runCheckpointing(
    deviceName: Name,
    messageFlow: Flow<DeviceMessage>,
    snapshotStore: SnapshotStore,
    strategy: CheckpointStrategy,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
): Job = when (strategy) {
    CheckpointStrategy.Manual -> scope.launch { /* no-op; completes immediately */ }

    is CheckpointStrategy.EveryNEvents -> {
        var counter = 0
        messageFlow
            .onEach {
                if (++counter >= strategy.n) {
                    counter = 0
                    snapshotStore.save(deviceName, captureSnapshot(clock.now()))
                }
            }
            .launchIn(scope)
    }

    is CheckpointStrategy.EveryDuration -> scope.launch {
        var latestSavedContent: Pair<Meta, Map<String, Meta>>? = null
        while (isActive) {
            delay(strategy.duration)
            val snapshot = captureSnapshot(clock.now())
            val content = snapshot.state to snapshot.capabilitySnapshots
            if (content != latestSavedContent) {
                snapshotStore.save(deviceName, snapshot)
                latestSavedContent = content
            }
        }
    }
}
