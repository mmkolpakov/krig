@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.analytics

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.data.await
import space.kscience.dataforge.data.get
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.workspace.Workspace
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.dataforge.asReplayWindowDataSource
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.krig.storage.journal.InMemoryEventJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ReplayWorkspaceActionsTest {
    private val workspace = Workspace {}
    private val inputName = "window".asName()

    @Test
    fun replayStateTaskReconstructsSnapshotFromLazyJournalWindow() = runTest {
        val selector = journalOf(0 to 0, 100 to 1, 200 to 2, 300 to 3)
            .asReplayWindowDataSource(
                name = inputName,
                from = Instant.fromEpochMilliseconds(100),
                until = Instant.fromEpochMilliseconds(300),
            )
            .asDataSelector(inputName)
        val task = replayStateTask(
            source = selector,
            reconstructible = ::CounterReplay,
            config = ReplayActionConfig(input = inputName, output = "state".asName()),
        )

        val result = task.execute(
            workspace = workspace,
            taskName = "replay".asName(),
            taskMeta = Meta { ReplayActionMetaKeys.AT_MS put 200L },
        )

        assertEquals(2, result["state"]?.await()?.state?.int)
    }

    @Test
    fun counterfactualStateTaskAppliesTypedMutationPlan() = runTest {
        val selector = journalOf(100 to 1, 200 to 2, 300 to 3)
            .asReplayWindowDataSource(
                name = inputName,
                from = Instant.fromEpochMilliseconds(100),
                until = Instant.fromEpochMilliseconds(300),
            )
            .asDataSelector(inputName)
        val task = counterfactualStateTask(
            source = selector,
            reconstructible = ::CounterReplay,
            mutationName = "plus-ten",
            mutator = { message ->
                val property = message as? PropertyChangedMessage ?: return@counterfactualStateTask message
                property.copy(value = Meta(((property.value.int ?: 0) + 10).asValue()))
            },
            config = ReplayActionConfig(input = inputName, output = "state".asName(), branch = "lab-what-if"),
        )

        val result = task.execute(workspace, "what-if".asName(), Meta { ReplayActionMetaKeys.AT_MS put 300L })

        assertEquals(13, result["state"]?.await()?.state?.int)
    }

    @Test
    fun replayMetricsTaskReportsBoundedWindow() = runTest {
        val selector = journalOf(0 to 0, 100 to 1, 200 to 2, 300 to 3)
            .asReplayWindowDataSource(
                name = inputName,
                from = Instant.fromEpochMilliseconds(0),
                until = Instant.fromEpochMilliseconds(300),
            )
            .asDataSelector(inputName)
        val task = replayWindowMetricsTask(
            source = selector,
            config = ReplayActionConfig(
                input = inputName,
                output = "metrics".asName(),
                from = Instant.fromEpochMilliseconds(100),
                until = Instant.fromEpochMilliseconds(200),
                branch = "bounded",
            ),
        )

        val metrics = task.execute(workspace, "metrics".asName(), Meta.EMPTY)["metrics"]?.await()

        assertEquals(2, metrics?.eventCount)
        assertEquals(100L, metrics?.firstEventTime?.toEpochMilliseconds())
        assertEquals(200L, metrics?.lastEventTime?.toEpochMilliseconds())
        assertEquals("bounded", metrics?.branch)
    }

    private suspend fun journalOf(vararg values: Pair<Int, Int>): InMemoryEventJournal {
        val journal = InMemoryEventJournal()
        values.forEach { (timeMs, value) ->
            journal.write(
                PropertyChangedMessage(
                    time = Instant.fromEpochMilliseconds(timeMs.toLong()),
                    property = "value".asName(),
                    value = Meta(value.asValue()),
                    sourceDevice = "lab.counter".asName(),
                ),
            )
        }
        return journal
    }

    private class CounterReplay : Reconstructible {
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
            value = snapshot.state.int ?: error("malformed snapshot")
        }
    }
}
