package space.kscience.krig.storage.journal

import kotlinx.serialization.Serializable

/**
 * Snapshot-side pointer to the exact event-log position covered by a checkpoint.
 *
 * The anchor is cursor-based by design. Event time remains useful for queries, but retention and
 * compaction must not infer safety from wall-clock timestamps in a distributed system.
 */
@Serializable
public data class CheckpointAnchor(
    public val coveredCursor: EventCursor? = null,
)

public typealias SnapshotAnchor = CheckpointAnchor
