@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.core.ExperimentalKrigApi
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Schema id for raw snapshot entries. */
@JvmInline
@Serializable
public value class SnapshotSchema(public val value: String) {
    init { require(value.isNotBlank()) { "SnapshotSchema must not be blank" } }
}

public object SnapshotSchemas {
    public val deviceSnapshotV1: SnapshotSchema = SnapshotSchema("krig.device-snapshot.v1")
}

/** Raw snapshot row. Storage keeps this form; recovery decodes/migrates it. */
@Serializable
public data class SnapshotEntry(
    public val subject: Name,
    public val at: Instant,
    public val schema: SnapshotSchema,
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
    private val schema: SnapshotSchema = SnapshotSchemas.deviceSnapshotV1,
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
