package space.kscience.krig.core.timetravel

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Versioned schema id for persisted time-travel rows (journal entries and snapshots). */
@JvmInline
@Serializable
public value class StorageSchema(public val value: String) {
    init { require(value.isNotBlank()) { "StorageSchema must not be blank" } }
}

/** Canonical KRig storage schema ids. */
public object StorageSchemas {
    public val deviceMessageV1: StorageSchema = StorageSchema("krig.device-message.v1")
    public val deviceSnapshotV1: StorageSchema = StorageSchema("krig.device-snapshot.v1")
}
