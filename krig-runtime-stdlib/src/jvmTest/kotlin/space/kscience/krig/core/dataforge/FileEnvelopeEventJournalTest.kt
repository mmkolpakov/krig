package space.kscience.krig.core.dataforge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.storage.journal.SequenceCursor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FileEnvelopeEventJournalTest {

    private val file: Path = Files.createTempFile("krig-journal", ".df")

    @AfterTest
    fun cleanup() {
        Files.deleteIfExists(file)
    }

    private fun frame(ms: Long, source: String): DeviceMessageFrame<DeviceMessage> =
        PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(ms),
            property = "value".asName(),
            value = Meta.EMPTY,
            sourceDevice = source.asName(),
        ).frame()

    @Test
    fun writeReadRoundTripWithFilters() = runTest {
        FileEnvelopeEventJournal(file).use { journal ->
            journal.write(frame(1, "a"))
            journal.write(frame(2, "b"))
            journal.write(frame(3, "a"))

            val all = journal.readAll().toList()
            assertEquals(listOf(1L, 2L, 3L), all.map { it.payload.time.toEpochMilliseconds() })

            val ranged = journal.read(range = Instant.fromEpochMilliseconds(2)..Instant.fromEpochMilliseconds(3)).toList()
            assertEquals(listOf(2L, 3L), ranged.map { it.payload.time.toEpochMilliseconds() })

            val fromA = journal.read(sourceDevice = "a".asName()).toList()
            assertEquals(listOf(1L, 3L), fromA.map { it.payload.time.toEpochMilliseconds() })
        }
    }

    @Test
    fun replayFromCursorSeeksByOffset() = runTest {
        FileEnvelopeEventJournal(file).use { journal ->
            journal.write(frame(1, "a"))
            val second = journal.write(frame(2, "a"))
            journal.write(frame(3, "a"))

            val tail = journal.replayFrom(second).toList()
            assertEquals(listOf(3L), tail.map { it.message.time.toEpochMilliseconds() })
            assertEquals(SequenceCursor(2), tail.single().cursor)
        }
    }

    @Test
    fun recoversAndAppendsAfterReopen() = runTest {
        FileEnvelopeEventJournal(file).use { journal ->
            journal.write(frame(1, "a"))
            journal.write(frame(2, "a"))
        }

        FileEnvelopeEventJournal(file).use { reopened ->
            assertEquals(2, reopened.readAll().toList().size)
            reopened.write(frame(3, "a"))
            assertEquals(listOf(1L, 2L, 3L), reopened.readAll().toList().map { it.payload.time.toEpochMilliseconds() })
        }
    }

    @Test
    fun toleratesTornTrailingBytes() = runTest {
        FileEnvelopeEventJournal(file).use { journal ->
            journal.write(frame(1, "a"))
            journal.write(frame(2, "a"))
        }
        Files.write(file, byteArrayOf(0x23, 0x7E, 0x01, 0x02, 0x03), StandardOpenOption.APPEND)

        FileEnvelopeEventJournal(file).use { recovered ->
            assertEquals(2, recovered.readAll().toList().size)
            recovered.write(frame(3, "a"))
            assertEquals(3, recovered.readAll().toList().size)
        }
    }
}
