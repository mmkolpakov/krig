package space.kscience.krig.core.timetravel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import kotlin.time.Clock
import kotlin.time.Duration

public class CheckpointContext(
    public val reconstructible: Reconstructible,
    public val subject: Name,
    public val messageFlow: Flow<DeviceMessage>,
    public val snapshotStore: SnapshotStore,
    public val scope: CoroutineScope,
    public val clock: Clock,
    public val snapshotCodec: SnapshotCodec,
    public val retentionPolicy: SnapshotRetentionPolicy,
)

/** Snapshot capture strategy. Integrations may provide their own implementation. */
public fun interface CheckpointStrategy {
    public fun start(context: CheckpointContext): Job

    public companion object {
        public val manual: CheckpointStrategy = CheckpointStrategy { context ->
            context.scope.launch { /* no-op; completes immediately */ }
        }

        public fun everyNEvents(n: Int): CheckpointStrategy {
            require(n > 0) { "CheckpointStrategy.everyNEvents requires n > 0, got $n" }
            return CheckpointStrategy { context ->
                var counter = 0
                context.messageFlow
                    .onEach {
                        if (++counter >= n) {
                            counter = 0
                            context.saveSnapshot()
                        }
                    }
                    .launchIn(context.scope)
            }
        }

        public fun everyDuration(duration: Duration): CheckpointStrategy {
            require(duration.isPositive()) { "CheckpointStrategy.everyDuration requires a positive duration" }
            return CheckpointStrategy { context ->
                context.scope.launch {
                    var latestSavedContent: Pair<Meta, Map<String, Meta>>? = null
                    while (isActive) {
                        delay(duration)
                        val snapshot = context.reconstructible.captureSnapshot(context.clock.now())
                        val content = snapshot.state to snapshot.capabilitySnapshots
                        if (content != latestSavedContent) {
                            context.snapshotStore.save(context.subject, snapshot, context.snapshotCodec)
                            context.snapshotStore.applyRetention(context.subject, context.retentionPolicy)
                            latestSavedContent = content
                        }
                    }
                }
            }
        }
    }
}

/**
 * Drive snapshotting for a [Reconstructible] tied to the [messageFlow] it folds.
 *
 * The returned [Job] owns the capture loop; cancelling the scope (or the job) stops
 * checkpointing. Built-in strategies are available from [CheckpointStrategy]'s companion:
 * `everyNEvents`, `everyDuration`, and `manual`.
 */
public fun Reconstructible.runCheckpointing(
    subject: Name,
    messageFlow: Flow<DeviceMessage>,
    snapshotStore: SnapshotStore,
    strategy: CheckpointStrategy,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
    snapshotCodec: SnapshotCodec = SnapshotCodec(),
    retentionPolicy: SnapshotRetentionPolicy = SnapshotRetentionPolicy.keepAll,
): Job = strategy.start(
    CheckpointContext(
        reconstructible = this,
        subject = subject,
        messageFlow = messageFlow,
        snapshotStore = snapshotStore,
        scope = scope,
        clock = clock,
        snapshotCodec = snapshotCodec,
        retentionPolicy = retentionPolicy,
    ),
)

private suspend fun CheckpointContext.saveSnapshot() {
    snapshotStore.save(subject, reconstructible.captureSnapshot(clock.now()), snapshotCodec)
    snapshotStore.applyRetention(subject, retentionPolicy)
}
