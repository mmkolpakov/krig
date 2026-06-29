@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.core.dataforge

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.data.await
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class KrigDataSourcesTest {

    @Test
    fun replayWindowDataSourceUsesBoundedRecords() = runTest {
        val journal = InMemoryEventJournal()
        listOf(0L, 100L, 200L, 300L).forEach { time ->
            journal.write(PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(time),
                property = "rpm".asName(),
                value = metaOf(time.toDouble()),
                sourceDevice = "stand".asName(),
            ))
        }
        val source = journal.asReplayWindowDataSource(
            name = "window".asName(),
            from = Instant.fromEpochMilliseconds(100),
            until = Instant.fromEpochMilliseconds(200),
        )

        val records = source.read("window".asName())!!.await()

        assertEquals(listOf(100L, 200L), records.map { it.message.time.toEpochMilliseconds() })
    }

    @Test
    fun denseChunkDataSourceKeepsChunkShape() = runTest {
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf("rpm".asName()),
            rows = listOf(
                DenseDoubleTimeSeriesRow(
                    time = Instant.fromEpochMilliseconds(1),
                    values = doubleArrayOf(1_500.0),
                ),
            ),
        )
        val source = chunk.asChunkDataSource("chunk".asName())

        val read = source.read(Name.EMPTY)!!.await()

        assertEquals(1, read.rowCount)
        assertEquals(1_500.0, read.value(row = 0, seriesIndex = 0))
    }
}
