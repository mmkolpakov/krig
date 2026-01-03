package space.kscience.controls.composite.persistence

import kotlinx.coroutines.test.runTest
import space.kscience.controls.common.serialization.Base64Bytes
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An abstract test suite for any SnapshotStore implementation.
 * Subclasses must provide a concrete [store] instance.
 */
abstract class SnapshotStoreTestBase {

    protected abstract val store: SnapshotStore

    private val testName = Name.parse("device.test")
    internal val testMeta = Meta {
        "value" put "testValue"
        "number" put 42
    }
    private val testBlobs = mapOf(
        Name.parse("blob1") to Base64Bytes("blob one".encodeToByteArray()),
        Name.parse("blob2") to Base64Bytes("blob two".encodeToByteArray())
    )

    @Test
    fun saveAndLoad() = runTest {
        assertNull(store.load(testName), "Store should be empty initially for name $testName")

        store.save(testName, testMeta)
        val loaded = store.load(testName)

        assertNotNull(loaded, "Snapshot should be loaded after saving")
        val (meta, blobs) = loaded
        assertEquals(testMeta, meta)
        assertEquals("testValue", meta["value"].string)
        assertEquals(42, meta["number"].int)
        assertNull(blobs)
    }

    @Test
    fun saveAndLoadWithBlobs() = runTest {
        store.save(testName, testMeta, testBlobs)
        val loaded = store.load(testName)

        assertNotNull(loaded, "Snapshot with blobs should be loaded after saving")
        val (meta, blobs) = loaded
        assertEquals(testMeta, meta)
        assertNotNull(blobs)
        assertEquals(2, blobs.size)
        assertEquals(testBlobs["blob1"]?.bytes?.decodeToString(), blobs["blob1"]?.bytes?.decodeToString())
        assertEquals(testBlobs["blob2"]?.bytes?.decodeToString(), blobs["blob2"]?.bytes?.decodeToString())
    }

    @Test
    fun overwrite() = runTest {
        store.save(testName, testMeta)

        val newSnapshot = Meta {
            "value" put "newValue"
            "number" put 99
        }
        val newBlobs = mapOf(Name.parse("newBlob") to Base64Bytes("new data".encodeToByteArray()))

        store.save(testName, newSnapshot, newBlobs)
        val loaded = store.load(testName)

        assertNotNull(loaded)
        val (meta, blobs) = loaded
        assertEquals(newSnapshot, meta)
        assertEquals("newValue", meta["value"].string)
        assertNotNull(blobs)
        assertEquals(1, blobs.size)
        assertEquals("new data", blobs["newBlob"]?.bytes?.decodeToString())
    }

    @Test
    fun delete() = runTest {
        store.save(testName, testMeta)
        assertNotNull(store.load(testName), "Snapshot should exist before deletion")

        store.delete(testName)
        assertNull(store.load(testName), "Snapshot should be null after deletion")
    }

    @Test
    fun deleteNonExistent() = runTest {
        // Should not throw
        store.delete(Name.parse("non.existent"))
    }
}