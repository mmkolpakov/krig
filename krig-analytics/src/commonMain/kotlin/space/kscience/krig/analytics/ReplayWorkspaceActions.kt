package space.kscience.krig.analytics

import kotlinx.coroutines.flow.asFlow
import space.kscience.dataforge.actions.Action
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.DataSource
import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.data.await
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.long
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.misc.UnsafeKType
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.workspace.DataSelector
import space.kscience.dataforge.workspace.Task
import space.kscience.dataforge.workspace.from
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.krig.core.timetravel.counterfactual
import space.kscience.krig.core.timetravel.timeTravel
import space.kscience.krig.storage.journal.ReplayLog
import space.kscience.krig.storage.journal.ReplayRecord
import kotlin.time.Instant

/** Meta keys understood by replay DataForge actions and tasks. */
public object ReplayActionMetaKeys {
    public val INPUT: Name = "krig.replay.input".parseAsName()
    public val OUTPUT: Name = "krig.replay.output".parseAsName()
    public val AT_MS: Name = "krig.replay.atMs".parseAsName()
    public val FROM_MS: Name = "krig.replay.window.fromMs".parseAsName()
    public val UNTIL_MS: Name = "krig.replay.window.untilMs".parseAsName()
    public val BRANCH: Name = "krig.replay.branch".parseAsName()
    public val MUTATION: Name = "krig.replay.mutation".parseAsName()
}

/**
 * Stable replay action configuration. DataForge [Meta] passed to the action may override these
 * fields through [ReplayActionMetaKeys].
 */
public data class ReplayActionConfig(
    public val input: Name = Name.EMPTY,
    public val output: Name = "state".parseAsName(),
    public val at: Instant? = null,
    public val from: Instant? = null,
    public val until: Instant? = null,
    public val branch: String? = null,
    public val mutation: String? = null,
)

/** Bounded replay metrics suitable for dashboards and workspace smoke checks. */
public data class ReplayWindowMetrics(
    public val eventCount: Int,
    public val firstEventTime: Instant?,
    public val lastEventTime: Instant?,
    public val branch: String?,
    public val mutation: String?,
)

/** Lift one node of a lazy [DataSource] into a [DataTree], the shape required by DataForge actions. */
@OptIn(UnsafeKType::class)
public fun <T> DataSource<T>.asSingleNodeDataTree(name: Name = Name.EMPTY): DataTree<T> = DataTree(dataType) {
    val data = read(name) ?: if (name == Name.EMPTY) null else read(Name.EMPTY)
    require(data != null) { "DataSource does not contain node '$name'." }
    put(name, data)
}

/** Workspace selector wrapper for a single-node [DataSource]. */
public fun <T> DataSource<T>.asDataSelector(name: Name = Name.EMPTY): DataSelector<T> =
    DataSelector { _, _ -> asSingleNodeDataTree(name) }

/**
 * Replays journal records into a fresh [Reconstructible] and emits the captured state snapshot.
 *
 * The reconstructible is supplied as a factory because DataForge [Data] is lazy: the same result node
 * may be awaited more than once, and sharing mutable replay state across awaits would make the action
 * order-dependent.
 */
public fun replayStateAction(
    reconstructible: () -> Reconstructible,
    config: ReplayActionConfig = ReplayActionConfig(),
    snapshot: DeviceSnapshot? = null,
): Action<List<ReplayRecord>, DeviceSnapshot> = Action { source, meta, _ ->
    val effective = config.withOverrides(meta)
    val recordsData = source.read(effective.input)
        ?: error("Replay action input '${effective.input}' is missing.")
    DataTree {
        put(effective.output, Data(meta = effective.toMeta(), dependencies = listOf(recordsData)) {
            val records = recordsData.await().boundedBy(effective)
            val at = effective.targetTime(records, snapshot)
            val model = reconstructible()
            model.timeTravel(at = at, log = records.replayLog(), snapshot = snapshot)
            model.captureSnapshot(at)
        })
    }
}

/**
 * Replays journal records after applying [mutator], then emits the resulting state snapshot.
 * The mutation itself stays typed code; DataForge [Meta] carries only the branch/mutation identity.
 */
public fun counterfactualStateAction(
    reconstructible: () -> Reconstructible,
    mutationName: String,
    mutator: (DeviceMessage) -> DeviceMessage,
    config: ReplayActionConfig = ReplayActionConfig(),
    snapshot: DeviceSnapshot? = null,
): Action<List<ReplayRecord>, DeviceSnapshot> = Action { source, meta, _ ->
    val effective = config.copy(mutation = config.mutation ?: mutationName).withOverrides(meta)
    val recordsData = source.read(effective.input)
        ?: error("Counterfactual action input '${effective.input}' is missing.")
    DataTree {
        put(effective.output, Data(meta = effective.toMeta(), dependencies = listOf(recordsData)) {
            val records = recordsData.await().boundedBy(effective)
            val at = effective.targetTime(records, snapshot)
            val model = reconstructible()
            model.counterfactual(log = records.replayLog(), at = at, snapshot = snapshot, mutator = mutator)
            model.captureSnapshot(at)
        })
    }
}

/** Emits basic metrics for the selected replay window without reconstructing device state. */
public fun replayWindowMetricsAction(
    config: ReplayActionConfig = ReplayActionConfig(output = "metrics".parseAsName()),
): Action<List<ReplayRecord>, ReplayWindowMetrics> = Action { source, meta, _ ->
    val effective = config.withOverrides(meta)
    val recordsData = source.read(effective.input)
        ?: error("Replay metrics input '${effective.input}' is missing.")
    DataTree {
        put(effective.output, Data(meta = effective.toMeta(), dependencies = listOf(recordsData)) {
            val records = recordsData.await().boundedBy(effective)
            ReplayWindowMetrics(
                eventCount = records.size,
                firstEventTime = records.firstOrNull()?.message?.time,
                lastEventTime = records.lastOrNull()?.message?.time,
                branch = effective.branch,
                mutation = effective.mutation,
            )
        })
    }
}

/** Workspace task wrapper around [replayStateAction]. */
public fun replayStateTask(
    source: DataSelector<List<ReplayRecord>>,
    reconstructible: () -> Reconstructible,
    config: ReplayActionConfig = ReplayActionConfig(),
    snapshot: DeviceSnapshot? = null,
): Task<DeviceSnapshot> = Task {
    replayStateAction(reconstructible, config, snapshot).execute(from(source), taskMeta, workspace)
}

/** Workspace task wrapper around [counterfactualStateAction]. */
public fun counterfactualStateTask(
    source: DataSelector<List<ReplayRecord>>,
    reconstructible: () -> Reconstructible,
    mutationName: String,
    mutator: (DeviceMessage) -> DeviceMessage,
    config: ReplayActionConfig = ReplayActionConfig(),
    snapshot: DeviceSnapshot? = null,
): Task<DeviceSnapshot> = Task {
    counterfactualStateAction(reconstructible, mutationName, mutator, config, snapshot)
        .execute(from(source), taskMeta, workspace)
}

/** Workspace task wrapper around [replayWindowMetricsAction]. */
public fun replayWindowMetricsTask(
    source: DataSelector<List<ReplayRecord>>,
    config: ReplayActionConfig = ReplayActionConfig(output = "metrics".parseAsName()),
): Task<ReplayWindowMetrics> = Task {
    replayWindowMetricsAction(config).execute(from(source), taskMeta, workspace)
}

private fun ReplayActionConfig.withOverrides(meta: Meta): ReplayActionConfig = copy(
    input = meta.nameOrNull(ReplayActionMetaKeys.INPUT) ?: input,
    output = meta.nameOrNull(ReplayActionMetaKeys.OUTPUT) ?: output,
    at = meta.instantOrNull(ReplayActionMetaKeys.AT_MS) ?: at,
    from = meta.instantOrNull(ReplayActionMetaKeys.FROM_MS) ?: from,
    until = meta.instantOrNull(ReplayActionMetaKeys.UNTIL_MS) ?: until,
    branch = meta[ReplayActionMetaKeys.BRANCH]?.string ?: branch,
    mutation = meta[ReplayActionMetaKeys.MUTATION]?.string ?: mutation,
)

private fun ReplayActionConfig.toMeta(): Meta = Meta {
    ReplayActionMetaKeys.INPUT put input.toString()
    ReplayActionMetaKeys.OUTPUT put output.toString()
    at?.let { ReplayActionMetaKeys.AT_MS put it.toEpochMilliseconds() }
    from?.let { ReplayActionMetaKeys.FROM_MS put it.toEpochMilliseconds() }
    until?.let { ReplayActionMetaKeys.UNTIL_MS put it.toEpochMilliseconds() }
    branch?.let { ReplayActionMetaKeys.BRANCH put it }
    mutation?.let { ReplayActionMetaKeys.MUTATION put it }
}

private fun Meta.nameOrNull(key: Name): Name? =
    get(key)?.string?.takeIf { it.isNotBlank() }?.parseAsName()

private fun Meta.instantOrNull(key: Name): Instant? =
    get(key)?.long?.let(Instant::fromEpochMilliseconds)

private fun ReplayActionConfig.targetTime(
    records: List<ReplayRecord>,
    snapshot: DeviceSnapshot?,
): Instant = at ?: until ?: records.lastOrNull()?.message?.time ?: snapshot?.at ?: Instant.DISTANT_PAST

private fun List<ReplayRecord>.boundedBy(config: ReplayActionConfig): List<ReplayRecord> =
    filter { record ->
        val time = record.message.time
        (config.from == null || time >= config.from) && (config.until == null || time <= config.until)
    }

private fun List<ReplayRecord>.replayLog(): ReplayLog =
    ReplayLog(map { it.envelope }.asFlow())
