package space.kscience.krig.core.timetravel

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class MetaJournalMigrationTest {
    private val subject = "lab.counter".asName()
    private val v2 = StorageSchema("demo.counter.v2")

    @Test
    fun movesPayloadNodeAndRestampsSchema() {
        val migration = metaJournalMigration(StorageSchemas.deviceMessageV1, v2) {
            move("oldSensor".asName(), "newSensor".asName())
        }
        val entry = JournalEntry(
            subject = subject,
            messageType = "demo.counter",
            schema = StorageSchemas.deviceMessageV1,
            time = Instant.fromEpochMilliseconds(10),
            payload = Meta { "oldSensor" put 7 },
        )

        val migrated = assertNotNull(migration.migrate(entry))

        assertEquals(v2, migrated.schema)
        assertEquals(7, migrated.payload["newSensor"]?.int)
        assertNull(migrated.payload["oldSensor"])
    }

    @Test
    fun passesThroughEntriesOnOtherSchema() {
        val migration = metaJournalMigration(StorageSchema("only.this"), v2) {
            move("a".asName(), "b".asName())
        }
        val entry = JournalEntry(
            subject = subject,
            messageType = "demo.counter",
            schema = StorageSchemas.deviceMessageV1,
            time = Instant.fromEpochMilliseconds(1),
            payload = Meta { "a" put 1 },
        )

        assertEquals(entry, migration.migrate(entry))
    }
}
