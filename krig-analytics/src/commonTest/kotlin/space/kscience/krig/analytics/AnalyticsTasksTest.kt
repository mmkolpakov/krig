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

    private fun reading(value: Double): PropertyChangedMessage = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(0),
        property = "reading".asName(),
        value = Meta { "v" put value },
        sourceDevice = "dev".asName(),
    )

    private suspend fun selectorOf(vararg values: Double): EventJournalDataSelector {
        val journal = InMemoryEventJournal()
        values.forEach { journal.write(reading(it)) }
        return EventJournalDataSelector(journal) { (it as? PropertyChangedMessage)?.value?.get("v")?.double }
    }

    @Test
    fun meanReducesJournalSamples() = runTest {
        val task = meanTask(selectorOf(2.0, 4.0, 6.0))

        val result = task.execute(workspace, "mean".asName(), Meta.EMPTY)

        assertEquals(4.0, result[Name.EMPTY]?.await())
    }

    @Test
    fun sumReducesJournalSamples() = runTest {
        val task = sumTask(selectorOf(1.0, 2.0, 3.0, 4.0))

        val result = task.execute(workspace, "sum".asName(), Meta.EMPTY)

        assertEquals(10.0, result[Name.EMPTY]?.await())
    }

    @Test
    fun meanOfEmptyJournalIsNaN() = runTest {
        val task = meanTask(selectorOf())

        val result = task.execute(workspace, "mean".asName(), Meta.EMPTY)

        assertEquals(Double.NaN, result[Name.EMPTY]?.await())
    }
}
