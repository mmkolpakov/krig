package space.kscience.controls.composite.persistence

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import space.kscience.controls.api.utils.Base64Bytes
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.names.Name
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class FileSnapshotStoreTest : SnapshotStoreTestBase() {
    private lateinit var fs: FakeFileSystem
    override lateinit var store: SnapshotStore

    @BeforeTest
    fun setup() {
        fs = FakeFileSystem()
        // Create the base directory inside the fake file system
        fs.createDirectories("/snapshots".toPath())
        store = FileSnapshotStore(Global, fs, "/snapshots")
    }

    @Test
    fun `save creates physical files`() = runTest {
        val testName = Name.parse("device.test")
        val testBlobs = mapOf(
            Name.parse("blob1") to Base64Bytes("blob one".encodeToByteArray()),
            Name.parse("blob2") to Base64Bytes("blob two".encodeToByteArray())
        )
        store.save(testName, testMeta, testBlobs)

        assertTrue(fs.exists("/snapshots/device.test/snapshot.json".toPath()))
        assertTrue(fs.exists("/snapshots/device.test/blobs/blob1".toPath()))
        assertTrue(fs.exists("/snapshots/device.test/blobs/blob2".toPath()))
    }
}