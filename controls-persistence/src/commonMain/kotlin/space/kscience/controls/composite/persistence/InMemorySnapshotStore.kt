package space.kscience.controls.composite.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.kscience.controls.api.utils.Base64Bytes
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A simple, in-memory implementation of [SnapshotStore].
 * State is lost when the application terminates. Useful for testing and temporary storage.
 */
public class InMemorySnapshotStore : SnapshotStore {
    private data class Snapshot(val meta: Meta, val blobs: Map<Name, Base64Bytes>?)

    private val storage = mutableMapOf<Name, Snapshot>()
    private val mutex = Mutex()

    override suspend fun save(name: Name, snapshot: Meta, blobs: Map<Name, Base64Bytes>?) {
        mutex.withLock {
            storage[name] = Snapshot(snapshot, blobs)
        }
    }

    override suspend fun load(name: Name): Pair<Meta, Map<Name, Base64Bytes>?>? = mutex.withLock {
        storage[name]?.let { it.meta to it.blobs }
    }

    override suspend fun delete(name: Name) {
        mutex.withLock {
            storage.remove(name)
        }
    }

    /**
     * A factory for creating [InMemorySnapshotStore] instances.
     */
    public companion object : SnapshotStoreFactory {
        override val type: String = "memory"

        override fun build(context: Context, meta: Meta): SnapshotStore = InMemorySnapshotStore()
    }
}