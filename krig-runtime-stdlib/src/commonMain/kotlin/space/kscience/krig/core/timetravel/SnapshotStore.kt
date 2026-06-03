@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.serialization.krigStorageJson
import space.kscience.krig.core.ExperimentalKrigApi
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.time.Instant

/** Raw snapshot row. Storage keeps this form; recovery decodes/migrates it. */
@Serializable
public data class SnapshotEntry(
    public val subject: Name,
    public val at: Instant,
    public val schema: StorageSchema,
    public val state: Meta,
    public val capabilitySnapshots: Map<String, Meta> = emptyMap(),
)

/** Transforms stored snapshots before recovery. */
public fun interface SnapshotMigration {
    public fun migrate(entry: SnapshotEntry): SnapshotEntry
}

public class SnapshotMigrations(
    private val migrations: List<SnapshotMigration> = emptyList(),
) {
    public constructor(vararg migrations: SnapshotMigration) : this(migrations.toList())

    public fun migrate(entry: SnapshotEntry): SnapshotEntry =
        migrations.fold(entry) { current, migration -> migration.migrate(current) }

    public companion object {
        public val empty: SnapshotMigrations = SnapshotMigrations()
    }
}

public class SnapshotCodec(
    private val migrations: SnapshotMigrations = SnapshotMigrations.empty,
    private val schema: StorageSchema = StorageSchemas.deviceSnapshotV1,
) {
    public fun encode(subject: Name, snapshot: DeviceSnapshot): SnapshotEntry =
        SnapshotEntry(
            subject = subject,
            at = snapshot.at,
            schema = schema,
            state = snapshot.state,
            capabilitySnapshots = snapshot.capabilitySnapshots,
        )

    public fun decode(entry: SnapshotEntry): DeviceSnapshot {
        val migrated = migrations.migrate(entry)
        return DeviceSnapshot(
            at = migrated.at,
            state = migrated.state,
            capabilitySnapshots = migrated.capabilitySnapshots,
        )
    }
}

/**
 * Snapshot store for replay baselines.
 *
 * Persistent integrations keep the same raw [SnapshotEntry] shape and migrate it before recovery.
 */
public interface SnapshotStore {
    /** Persist [snapshot]. Snapshots for the same subject and instant are overwritten. */
    public suspend fun save(snapshot: SnapshotEntry)

    /** Closest snapshot for [subject] with `at <= threshold`, or `null` if none exist. */
    public suspend fun latestBefore(subject: Name, threshold: Instant): SnapshotEntry?

    /** All retained snapshots for [subject], oldest first. */
    public fun read(subject: Name): Flow<SnapshotEntry>

    /**
     * Remove snapshots for [subject]. `null` [olderThan] deletes all snapshots; otherwise
     * deletes only those with `at < olderThan`.
     */
    public suspend fun delete(subject: Name, olderThan: Instant? = null)
}

public suspend fun SnapshotStore.save(
    subject: Name,
    snapshot: DeviceSnapshot,
    codec: SnapshotCodec = SnapshotCodec(),
): Unit = save(codec.encode(subject, snapshot))

public suspend fun SnapshotStore.latestSnapshotBefore(
    subject: Name,
    threshold: Instant,
    codec: SnapshotCodec = SnapshotCodec(),
): DeviceSnapshot? = latestBefore(subject, threshold)?.let(codec::decode)

/**
 * CAS-backed in-memory [SnapshotStore] for tests and embedded scenarios.
 */
@ExperimentalKrigApi
public class InMemorySnapshotStore : SnapshotStore {
    private val state: AtomicReference<Map<Name, List<SnapshotEntry>>> =
        AtomicReference(emptyMap())

    override suspend fun save(snapshot: SnapshotEntry) {
        state.update { prev ->
            val priorList = prev[snapshot.subject].orEmpty()
            val withoutConflict = priorList.filterNot { it.at == snapshot.at }
            val nextList = (withoutConflict + snapshot).sortedBy { it.at }
            prev + (snapshot.subject to nextList)
        }
    }

    override suspend fun latestBefore(subject: Name, threshold: Instant): SnapshotEntry? =
        state.load()[subject]
            ?.asReversed()
            ?.firstOrNull { it.at <= threshold }

    override fun read(subject: Name): Flow<SnapshotEntry> =
        state.load()[subject].orEmpty().asFlow()

    override suspend fun delete(subject: Name, olderThan: Instant?) {
        state.update { prev ->
            val priorList = prev[subject] ?: return@update prev
            val nextList = if (olderThan == null) emptyList() else priorList.filter { it.at >= olderThan }
            if (nextList.isEmpty()) prev - subject else prev + (subject to nextList)
        }
    }
}

/**
 * Durable medium for serialized snapshot rows, keyed by subject and instant.
 *
 * The persistence seam analogous to the journal's raw row store: durable integrations (file, SQL,
 * key-value, object storage) implement these four operations over opaque [String] blobs, and
 * [SerializingSnapshotStore] adapts the medium to a typed [SnapshotStore]. Backends never need to
 * understand [SnapshotEntry] — only store, scan, and prune rows ordered by [at].
 */
public interface SnapshotBlobStore {
    /** Persist [blob] for [subject] at [at]. A blob for the same subject and instant is overwritten. */
    public suspend fun put(subject: Name, at: Instant, blob: String)

    /** Closest blob for [subject] with `at <= threshold`, or `null` if none exist. */
    public suspend fun latestBefore(subject: Name, threshold: Instant): String?

    /** All retained blobs for [subject], oldest first. */
    public fun list(subject: Name): Flow<String>

    /** Remove blobs for [subject]; `null` [olderThan] deletes all, otherwise those with `at < olderThan`. */
    public suspend fun delete(subject: Name, olderThan: Instant?)
}

/**
 * [SnapshotStore] that persists entries as serialized rows in a durable [SnapshotBlobStore], using
 * the same [krigStorageJson] line as the rest of krig storage. Pair this with the same backend that
 * implements [space.kscience.krig.storage.journal.EventJournal] to keep events and snapshots durable
 * together.
 */
public class SerializingSnapshotStore(
    private val blobs: SnapshotBlobStore,
    private val json: Json = krigStorageJson(),
) : SnapshotStore {
    override suspend fun save(snapshot: SnapshotEntry): Unit =
        blobs.put(snapshot.subject, snapshot.at, json.encodeToString(SnapshotEntry.serializer(), snapshot))

    override suspend fun latestBefore(subject: Name, threshold: Instant): SnapshotEntry? =
        blobs.latestBefore(subject, threshold)?.let { json.decodeFromString(SnapshotEntry.serializer(), it) }

    override fun read(subject: Name): Flow<SnapshotEntry> =
        blobs.list(subject).map { json.decodeFromString(SnapshotEntry.serializer(), it) }

    override suspend fun delete(subject: Name, olderThan: Instant?): Unit = blobs.delete(subject, olderThan)
}

/** CAS-backed in-memory [SnapshotBlobStore]; the reference durable medium for tests and embedding. */
@ExperimentalKrigApi
public class InMemorySnapshotBlobStore : SnapshotBlobStore {
    private data class BlobRow(val at: Instant, val blob: String)

    private val state: AtomicReference<Map<Name, List<BlobRow>>> = AtomicReference(emptyMap())

    override suspend fun put(subject: Name, at: Instant, blob: String) {
        state.update { prev ->
            val priorList = prev[subject].orEmpty().filterNot { it.at == at }
            prev + (subject to (priorList + BlobRow(at, blob)).sortedBy { it.at })
        }
    }

    override suspend fun latestBefore(subject: Name, threshold: Instant): String? =
        state.load()[subject]
            ?.asReversed()
            ?.firstOrNull { it.at <= threshold }
            ?.blob

    override fun list(subject: Name): Flow<String> =
        state.load()[subject].orEmpty().map { it.blob }.asFlow()

    override suspend fun delete(subject: Name, olderThan: Instant?) {
        state.update { prev ->
            val priorList = prev[subject] ?: return@update prev
            val nextList = if (olderThan == null) emptyList() else priorList.filter { it.at >= olderThan }
            if (nextList.isEmpty()) prev - subject else prev + (subject to nextList)
        }
    }
}
