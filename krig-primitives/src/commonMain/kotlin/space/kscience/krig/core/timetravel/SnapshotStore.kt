@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.core.timetravel

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.time.Instant
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.dataforge.names.Name

/**
 * Temporal snapshot store — multiple [DeviceSnapshot]s per subject, keyed by `(Name, Instant)`.
 * The core SPI that event-sourcing integrations implement (SQLite, file journal, LocalStorage).
 * [Reconstructible.timeTravel] uses [latestBefore] to locate the nearest baseline; without a
 * store the caller falls back to replaying the full log.
 */
public interface SnapshotStore {
    /** Persist [snapshot] for [subject]. Snapshots at the same `at` are overwritten. */
    public suspend fun save(subject: Name, snapshot: DeviceSnapshot)

    /** Closest snapshot for [subject] with `at <= threshold`, or `null` if none exist. */
    public suspend fun latestBefore(subject: Name, threshold: Instant): DeviceSnapshot?

    /**
     * Remove snapshots for [subject]. `null` [olderThan] deletes all snapshots; otherwise
     * deletes only those with `at < olderThan`.
     */
    public suspend fun delete(subject: Name, olderThan: Instant? = null)
}

/**
 * CAS-backed in-memory [SnapshotStore] for tests and embedded scenarios. Thread-safe via
 * `AtomicReference` on an immutable per-device snapshot map. Production deployments back
 * [SnapshotStore] by a persistent integration. This implementation is not durable and
 * keeps all retained snapshots in process memory.
 */
@ExperimentalKrigApi
public class InMemorySnapshotStore : SnapshotStore {
    private val state: AtomicReference<Map<Name, List<DeviceSnapshot>>> =
        AtomicReference(emptyMap())

    override suspend fun save(subject: Name, snapshot: DeviceSnapshot) {
        state.update { prev ->
            val priorList = prev[subject].orEmpty()
            val withoutConflict = priorList.filterNot { it.at == snapshot.at }
            val nextList = (withoutConflict + snapshot).sortedBy { it.at }
            prev + (subject to nextList)
        }
    }

    override suspend fun latestBefore(subject: Name, threshold: Instant): DeviceSnapshot? =
        state.load()[subject]
            ?.asReversed()
            ?.firstOrNull { it.at <= threshold }

    override suspend fun delete(subject: Name, olderThan: Instant?) {
        state.update { prev ->
            val priorList = prev[subject] ?: return@update prev
            val nextList = if (olderThan == null) emptyList() else priorList.filter { it.at >= olderThan }
            if (nextList.isEmpty()) prev - subject else prev + (subject to nextList)
        }
    }
}
