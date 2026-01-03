package space.kscience.controls.composite.persistence

import kotlinx.browser.localStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.w3c.dom.set
import space.kscience.controls.common.serialization.Base64Bytes
import space.kscience.controls.core.serialization.defaultCoreJson
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.toStringUnescaped

/**
 * A serializable container to store both meta and blob data as a single JSON object in localStorage.
 * Blobs are stored as Base64 encoded strings.
 */
@Serializable
private data class StoredSnapshot(
    val meta: Meta,
    val blobs: Map<Name, Base64Bytes>? = null,
)

/**
 * A [SnapshotStore] implementation that saves snapshots to the browser's localStorage.
 * Data persists across browser sessions.
 * Note: localStorage has size limits (usually around 5-10 MB). To handle binary data,
 * this implementation Base64-encodes byte arrays.
 *
 * @param keyPrefix A prefix to be used for all keys in localStorage to avoid collisions.
 */
internal class LocalStorageSnapshotStore(private val keyPrefix: String = "controls.snapshot.") : SnapshotStore {
    private val mutex = Mutex()

    private fun nameToKey(name: Name): String = "$keyPrefix${name.toStringUnescaped()}"

    override suspend fun save(name: Name, snapshot: Meta, blobs: Map<Name, Base64Bytes>?) {
        mutex.withLock {
            try {
                val key = nameToKey(name)
                val storable = StoredSnapshot(snapshot, blobs)
//                TODO improve json cross module logic
                val snapshotString = defaultCoreJson.encodeToString(storable)
                localStorage[key] = snapshotString
            } catch (e: Exception) {
                //TODO check for QUOTA_EXCEEDED_ERR
                throw SnapshotStoreException("Failed to save snapshot for '$name' to localStorage. Reason: ${e.message}", e)
            }
        }
    }

    override suspend fun load(name: Name): Pair<Meta, Map<Name, Base64Bytes>?>? = mutex.withLock {
        try {
            val key = nameToKey(name)
            localStorage.getItem(key)?.let { snapshotString ->
                //                TODO improve json cross module logic
                val stored = defaultCoreJson.decodeFromString<StoredSnapshot>(snapshotString)
                stored.meta to stored.blobs
            }
        } catch (e: Exception) {
            throw SnapshotFormatException("Failed to load snapshot for '$name' from localStorage. Reason: ${e.message}", e)
        }
    }

    override suspend fun delete(name: Name) {
        mutex.withLock {
            try {
                val key = nameToKey(name)
                localStorage.removeItem(key)
            } catch (e: Exception) {
                throw SnapshotStoreException("Failed to delete snapshot for '$name' from localStorage. Reason: ${e.message}", e)
            }
        }
    }

    /**
     * A factory for creating [LocalStorageSnapshotStore] instances.
     */
    companion object : SnapshotStoreFactory {
        override val type: String = "localStorage"

        override fun build(context: Context, meta: Meta): SnapshotStore = LocalStorageSnapshotStore()
    }
}