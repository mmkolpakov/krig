package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/** Snapshot retention hook. Default is conservative: keep all history. */
public fun interface SnapshotRetentionPolicy {
    public suspend fun apply(subject: Name, store: SnapshotStore)

    public companion object {
        public val keepAll: SnapshotRetentionPolicy = SnapshotRetentionPolicy { _, _ -> }

        public fun deleteOlderThan(threshold: Instant): SnapshotRetentionPolicy =
            SnapshotRetentionPolicy { subject, store ->
                store.delete(subject, olderThan = threshold)
            }

        public fun keepNewest(count: Int): SnapshotRetentionPolicy {
            require(count > 0) { "SnapshotRetentionPolicy.keepNewest requires count > 0, got $count" }
            return SnapshotRetentionPolicy { subject, store ->
                val entries = store.read(subject).toList().sortedByDescending { it.at }
                if (entries.size <= count) return@SnapshotRetentionPolicy
                val cutoff = entries[count - 1].at
                store.delete(subject, olderThan = cutoff)
            }
        }
    }
}

public suspend fun SnapshotStore.applyRetention(subject: Name, policy: SnapshotRetentionPolicy): Unit =
    policy.apply(subject, this)
