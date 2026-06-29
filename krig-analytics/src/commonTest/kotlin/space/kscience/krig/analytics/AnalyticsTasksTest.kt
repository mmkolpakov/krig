package space.kscience.krig.analytics

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.data.await
import space.kscience.dataforge.data.get
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.workspace.Workspace
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.krig.storage.journal.InMemoryEventJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalKrigApi::class)
class AnalyticsTasksTest {

    private val workspace: Workspace = Workspace {}

    private fun reading(value: Double, timeMs: Long = 0): PropertyChangedMessage = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(timeMs),
        property = "reading".asName(),
        value = Meta { "v" put value },
        sourceDevice = "dev".asName(),
    )

    private suspend fun snapshotSelectorOf(vararg values: Double): EventJournalSnapshotDataSelector {
        val journal = InMemoryEventJournal()
        values.forEach { journal.write(reading(it)) }
        return EventJournalSnapshotDataSelector(journal) { (it as? PropertyChangedMessage)?.value?.get("v")?.double }
    }

    private suspend fun replayWindowSelectorOf(
        fromMs: Long,
        untilMs: Long,
        vararg values: Pair<Long, Double>,
    ): EventJournalReplayWindowDataSelector {
        val journal = InMemoryEventJournal()
        values.forEach { (timeMs, value) -> journal.write(reading(value, timeMs)) }
        return EventJournalReplayWindowDataSelector(
            journal = journal,
            from = Instant.fromEpochMilliseconds(fromMs),
            until = Instant.fromEpochMilliseconds(untilMs),
        ) { (it as? PropertyChangedMessage)?.value?.get("v")?.double }
    }

    @Test
    fun meanReducesJournalSamples() = runTest {
        val task = meanTask(snapshotSelectorOf(2.0, 4.0, 6.0))

        val result = task.execute(workspace, "mean".asName(), Meta.EMPTY)

        assertEquals(4.0, result[Name.EMPTY]?.await())
    }

    @Test
    fun sumReducesJournalSamples() = runTest {
        val task = sumTask(snapshotSelectorOf(1.0, 2.0, 3.0, 4.0))

        val result = task.execute(workspace, "sum".asName(), Meta.EMPTY)

        assertEquals(10.0, result[Name.EMPTY]?.await())
    }

    @Test
    fun meanOfEmptyJournalIsNaN() = runTest {
        val task = meanTask(snapshotSelectorOf())

        val result = task.execute(workspace, "mean".asName(), Meta.EMPTY)

        assertEquals(Double.NaN, result[Name.EMPTY]?.await())
    }

    @Test
    fun replayWindowSelectorBoundsJournalSamples() = runTest {
        val task = sumTask(
            replayWindowSelectorOf(
                fromMs = 100,
                untilMs = 250,
                0L to 1.0,
                100L to 2.0,
                200L to 4.0,
                300L to 8.0,
            ),
        )

        val result = task.execute(workspace, "window".asName(), Meta.EMPTY)

        assertEquals(6.0, result[Name.EMPTY]?.await())
    }

    @Test
    fun snapshotReductionTaskMakesListBoundaryExplicit() = runTest {
        val task = snapshotReductionTask(snapshotSelectorOf(1.0, 3.0, 5.0)) { samples ->
            samples.maxOrNull() ?: Double.NaN
        }

        val result = task.execute(workspace, "max".asName(), Meta.EMPTY)

        assertEquals(5.0, result[Name.EMPTY]?.await())
    }
}
