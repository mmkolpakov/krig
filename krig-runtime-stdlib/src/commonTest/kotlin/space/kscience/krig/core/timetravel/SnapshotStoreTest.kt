@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotStoreTest {

    private fun snap(atMs: Long, value: Int): DeviceSnapshot = DeviceSnapshot(
        at = Instant.fromEpochMilliseconds(atMs),
        state = Meta(value.asValue()),
    )

    @Test
    fun latestBeforeReturnsClosestSnapshot() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.save(dev, snap(atMs = 200, value = 2))
        store.save(dev, snap(atMs = 300, value = 3))

        val s = store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(250))
        assertEquals(200L, s?.at?.toEpochMilliseconds())
    }

    @Test
    fun latestBeforeReturnsNullWhenNoSnapshotQualifies() = runTest {
        val store = InMemorySnapshotStore()
        store.save("dev".asName(), snap(atMs = 500, value = 9))
        val s = store.latestSnapshotBefore("dev".asName(), Instant.fromEpochMilliseconds(100))
        assertNull(s)
    }

    @Test
    fun saveOverwritesOnIdenticalTimestamp() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.save(dev, snap(atMs = 100, value = 42))
        val s = store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(100))
        assertEquals(42, s?.state?.int)
    }

    @Test
    fun deletePurgesOlderSnapshots() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.save(dev, snap(atMs = 200, value = 2))
        store.save(dev, snap(atMs = 300, value = 3))
        store.delete(dev, olderThan = Instant.fromEpochMilliseconds(200))
        val latestBefore200 = store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(199))
        assertNull(latestBefore200)
        val latestBefore300 = store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(300))
        assertEquals(300L, latestBefore300?.at?.toEpochMilliseconds())
    }

    @Test
    fun deleteAllRemovesDevice() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.delete(dev, olderThan = null)
        assertNull(store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(1000)))
    }

    @Test
    fun retentionKeepsNewestSnapshots() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.save(dev, snap(atMs = 200, value = 2))
        store.save(dev, snap(atMs = 300, value = 3))

        store.applyRetention(dev, SnapshotRetentionPolicy.keepNewest(2))

        assertNull(store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(150)))
        assertEquals(2, store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(250))?.state?.int)
        assertEquals(3, store.latestSnapshotBefore(dev, Instant.fromEpochMilliseconds(350))?.state?.int)
    }

    @Test
    fun snapshotCodecMigratesStoredEntry() {
        val oldSchema = SnapshotSchema("demo.snapshot.v0")
        val migration: SnapshotMigration = { entry ->
            if (entry.schema == oldSchema) {
                entry.copy(state = Meta(11.asValue()))
            } else {
                entry
            }
        }
        val codec = SnapshotCodec(
            migrations = SnapshotMigrations(migration),
        )
        val entry = SnapshotEntry(
            subject = "dev".asName(),
            at = Instant.fromEpochMilliseconds(100),
            schema = oldSchema,
            state = Meta(1.asValue()),
        )

        assertEquals(11, codec.decode(entry).state.int)
    }
}
