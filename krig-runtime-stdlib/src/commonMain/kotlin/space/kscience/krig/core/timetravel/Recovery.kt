package space.kscience.krig.core.timetravel

import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/** Declarative recovery inputs for replaying a reconstructible model. */
public class RecoveryBuilder {
    public var subject: Name? = null
    public var at: Instant? = null
    public var log: ReplayLog? = null
    public var snapshots: SnapshotStore? = null
    public var snapshotCodec: SnapshotCodec = SnapshotCodec()
}

public suspend fun Reconstructible.recover(body: RecoveryBuilder.() -> Unit) {
    val builder = RecoveryBuilder().apply(body)
    val subject = requireNotNull(builder.subject) { "Recovery subject is required" }
    val at = requireNotNull(builder.at) { "Recovery target time is required" }
    val log = requireNotNull(builder.log) { "Recovery replay log is required" }

    val snapshot = builder.snapshots
        ?.latestSnapshotBefore(subject, at, builder.snapshotCodec)
    timeTravel(at, log, snapshot)
}
