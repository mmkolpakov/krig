package space.kscience.krig.demo

import space.kscience.dataforge.data.await
import space.kscience.dataforge.data.get
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.workspace.Workspace
import space.kscience.krig.analytics.ReplayActionConfig
import space.kscience.krig.analytics.asDataSelector
import space.kscience.krig.analytics.counterfactualStateTask
import space.kscience.krig.analytics.replayStateTask
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.dataforge.asReplayWindowDataSource
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.krig.storage.journal.InMemoryEventJournal
import kotlin.time.Instant

internal data class ReplayWhatIfWorkspaceSnapshot(
    val baselineValue: Int,
    val whatIfValue: Int,
)

/** DataForge Workspace replay action: reconstruct baseline state and one counterfactual branch. */
suspend fun replayWhatIfWorkspaceDemo() {
    val snapshot = replayWhatIfWorkspaceSnapshot()

    println("=== Replay workspace what-if ===")
    println("  baseline value: ${snapshot.baselineValue}")
    println("  what-if value: ${snapshot.whatIfValue}")
    println("\nDone - replay workspace what-if demo complete.")
}

internal suspend fun replayWhatIfWorkspaceSnapshot(): ReplayWhatIfWorkspaceSnapshot {
    val inputName = "counter.window".asName()
    val stateName = "state".asName()
    val journal = InMemoryEventJournal()
    listOf(100L to 1, 200L to 2, 300L to 3).forEach { (timeMs, value) ->
        journal.write(counterMessage(timeMs, value))
    }
    val selector = journal
        .asReplayWindowDataSource(
            name = inputName,
            from = Instant.fromEpochMilliseconds(100),
            until = Instant.fromEpochMilliseconds(300),
        )
        .asDataSelector(inputName)
    val workspace = Workspace {}
    val config = ReplayActionConfig(input = inputName, output = stateName, at = Instant.fromEpochMilliseconds(300))
    val baseline = replayStateTask(selector, ::WorkspaceCounterReplay, config)
        .execute(workspace, "baseline".asName(), Meta.EMPTY)[stateName]
        ?.await()
        ?: error("Missing baseline snapshot")
    val whatIf = counterfactualStateTask(
        source = selector,
        reconstructible = ::WorkspaceCounterReplay,
        mutationName = "plus-ten",
        mutator = { message ->
            if (message is PropertyChangedMessage && message.property == "value".asName()) {
                message.copy(value = Meta(((message.value.int ?: 0) + 10).asValue()))
            } else {
                message
            }
        },
        config = config.copy(branch = "plus-ten"),
    ).execute(workspace, "what-if".asName(), Meta.EMPTY)[stateName]
        ?.await()
        ?: error("Missing counterfactual snapshot")

    return ReplayWhatIfWorkspaceSnapshot(
        baselineValue = baseline.state.int ?: error("Baseline state is not scalar"),
        whatIfValue = whatIf.state.int ?: error("Counterfactual state is not scalar"),
    )
}

private fun counterMessage(timeMs: Long, value: Int): PropertyChangedMessage =
    PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(timeMs),
        property = "value".asName(),
        value = Meta(value.asValue()),
        sourceDevice = "lab.counter".asName(),
    )

private class WorkspaceCounterReplay : Reconstructible {
    private var value: Int = 0

    override suspend fun applyEvent(event: DeviceMessage) {
        val message = event as? PropertyChangedMessage ?: return
        if (message.property == "value".asName()) {
            value = message.value.int ?: value
        }
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = Meta(value.asValue()))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        value = snapshot.state.int ?: error("Malformed replay snapshot")
    }
}
