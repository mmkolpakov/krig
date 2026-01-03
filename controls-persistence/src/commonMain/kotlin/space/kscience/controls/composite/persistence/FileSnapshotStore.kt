package space.kscience.controls.composite.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import space.kscience.controls.common.serialization.Base64Bytes
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.debug
import space.kscience.dataforge.context.error
import space.kscience.dataforge.context.info
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.io.JsonMetaFormat
import space.kscience.dataforge.io.parse
import space.kscience.dataforge.io.toString
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.toStringUnescaped

//TODO make configurable, not const?
private const val META_FILE_NAME = "snapshot.json"
private const val BLOBS_DIR_NAME = "blobs"

/**
 * A multiplatform implementation of [SnapshotStore] that saves snapshots to files on the local filesystem
 * using `okio` for file operations and `dataforge-io` for serialization.
 * Each snapshot is stored in its own directory, containing a `snapshot.json` for metadata
 * and a `blobs` subdirectory for any binary data.
 *
 * @param context The context, used for logging.
 * @param fs The [FileSystem] implementation to use for file operations.
 * @param basePath The base directory where snapshot directories will be stored.
 */
public class FileSnapshotStore(
    private val context: Context,
    private val fs: FileSystem,
    basePath: String,
) : SnapshotStore {
    private val basePath: Path = basePath.toPath()
    private val mutex = Mutex()
    private val metaFormat = JsonMetaFormat

    init {
        if (!fs.exists(this.basePath)) {
            context.logger.info { "Creating snapshot directory at '${this.basePath}'" }
            fs.createDirectories(this.basePath, mustCreate = false)
        }
    }

    private fun nameToPath(name: Name): Path = this.basePath / name.toStringUnescaped()

    override suspend fun save(name: Name, snapshot: Meta, blobs: Map<Name, Base64Bytes>?) {
        mutex.withLock {
            val snapshotDir = nameToPath(name)
            try {
                // Atomically replace the directory
                if (fs.exists(snapshotDir)) {
                    fs.deleteRecursively(snapshotDir)
                }
                fs.createDirectories(snapshotDir)

                // Save meta
                val metaFile = snapshotDir / META_FILE_NAME
                val snapshotString = snapshot.toString(metaFormat)
                fs.write(metaFile) {
                    writeUtf8(snapshotString)
                }

                // Save blobs
                if (!blobs.isNullOrEmpty()) {
                    val blobsDir = snapshotDir / BLOBS_DIR_NAME
                    fs.createDirectories(blobsDir)
                    blobs.forEach { (blobName, base64Bytes) ->
                        val blobFile = blobsDir / blobName.toStringUnescaped()
                        fs.write(blobFile) {
                            write(base64Bytes.bytes)
                        }
                    }
                }
                context.logger.debug { "Saved snapshot for '$name' to '$snapshotDir'." }
            } catch (e: Exception) {
                context.logger.error(e) { "Failed to save snapshot for '$name'." }
                // Clean up partially created snapshot directory on failure
                fs.deleteRecursively(snapshotDir, mustExist = false)
            }
        }
    }

    override suspend fun load(name: Name): Pair<Meta, Map<Name, Base64Bytes>?>? = mutex.withLock {
        val snapshotDir = nameToPath(name)
        if (!fs.exists(snapshotDir)) return@withLock null

        try {
            // Load meta
            val metaFile = snapshotDir / META_FILE_NAME
            if (!fs.exists(metaFile)) throw SnapshotFormatException("Snapshot for '$name' is corrupted: missing snapshot.json")
            val snapshotString = fs.read(metaFile) { readUtf8() }
            val meta = metaFormat.parse(snapshotString)

            // Load blobs
            val blobsDir = snapshotDir / BLOBS_DIR_NAME
            val blobs: Map<Name, Base64Bytes>? = if (fs.exists(blobsDir)) {
                fs.list(blobsDir).associate { blobFile ->
                    val blobName = blobFile.name.asName()
                    val bytes = fs.read(blobFile) { readByteArray() }
                    blobName to Base64Bytes(bytes)
                }
            } else {
                null
            }

            context.logger.debug { "Loaded snapshot for '$name' from '$snapshotDir'." }
            meta to blobs
        } catch (e: Exception) {
            context.logger.error(e) { "Failed to load snapshot for '$name' from '$snapshotDir'." }
            if (e !is SnapshotFormatException) throw SnapshotFormatException("Failed to load snapshot for '$name'", e) else throw e
        }
    }

    override suspend fun delete(name: Name) {
        mutex.withLock {
            val snapshotDir = nameToPath(name)
            try {
                fs.deleteRecursively(snapshotDir, mustExist = false)
                context.logger.debug { "Deleted snapshot for '$name' at '$snapshotDir'." }
            } catch (e: Exception) {
                context.logger.error(e) { "Failed to delete snapshot for '$name' at '$snapshotDir'." }
            }
        }
    }

    /**
     * A factory for creating [FileSnapshotStore] instances.
     */
    public companion object : SnapshotStoreFactory {
        override val type: String = "file"

        override fun build(context: Context, meta: Meta): SnapshotStore {
            val path = meta["path"].string ?: error("File snapshot store requires a 'path' to be configured.")
            val fsName = meta["fileSystem"].string ?: "SYSTEM"
            val fileSystem = context.fileSystemManager.get(fsName.asName())
            return FileSnapshotStore(context, fileSystem, path)
        }
    }
}