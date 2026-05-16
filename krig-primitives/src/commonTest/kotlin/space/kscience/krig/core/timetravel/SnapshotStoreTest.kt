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

        val s = store.latestBefore(dev, Instant.fromEpochMilliseconds(250))
        assertEquals(200L, s?.at?.toEpochMilliseconds())
    }

    @Test
    fun latestBeforeReturnsNullWhenNoSnapshotQualifies() = runTest {
        val store = InMemorySnapshotStore()
        store.save("dev".asName(), snap(atMs = 500, value = 9))
        val s = store.latestBefore("dev".asName(), Instant.fromEpochMilliseconds(100))
        assertNull(s)
    }

    @Test
    fun saveOverwritesOnIdenticalTimestamp() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.save(dev, snap(atMs = 100, value = 42))
        val s = store.latestBefore(dev, Instant.fromEpochMilliseconds(100))
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
        val latestBefore200 = store.latestBefore(dev, Instant.fromEpochMilliseconds(199))
        assertNull(latestBefore200)
        val latestBefore300 = store.latestBefore(dev, Instant.fromEpochMilliseconds(300))
        assertEquals(300L, latestBefore300?.at?.toEpochMilliseconds())
    }

    @Test
    fun deleteAllRemovesDevice() = runTest {
        val store = InMemorySnapshotStore()
        val dev = "dev".asName()
        store.save(dev, snap(atMs = 100, value = 1))
        store.delete(dev, olderThan = null)
        assertNull(store.latestBefore(dev, Instant.fromEpochMilliseconds(1000)))
    }
}
