@file:MustUseReturnValues

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * Replays events from [log] (starting at [snapshot]'s time or `DISTANT_PAST`) and stops
 * once [predicate] matches. The matching event IS applied — invert the predicate to halt
 * before it.
 *
 * Returns the matched event, or `null` if [until] is reached without a match.
 */
public suspend fun Reconstructible.replayUntil(
    log: DeviceEventLog,
    snapshot: DeviceSnapshot? = null,
    until: Instant = Instant.DISTANT_FUTURE,
    predicate: (DeviceMessage) -> Boolean,
): DeviceMessage? {
    val from = snapshot?.at ?: Instant.DISTANT_PAST
    if (snapshot != null) restoreSnapshot(snapshot)
    var matched: DeviceMessage? = null
    log.replay(from, until).transformWhile { event ->
        emit(event)
        if (predicate(event)) {
            matched = event
            false
        } else {
            true
        }
    }.collect { applyEvent(it) }
    return matched
}

/**
 * Replays [log] after applying [mutator] to each event. "What if this sensor read 5°
 * higher?" etc. Mutator must be deterministic for reproducibility.
 */
public suspend fun Reconstructible.counterfactual(
    log: DeviceEventLog,
    at: Instant,
    snapshot: DeviceSnapshot? = null,
    mutator: (DeviceMessage) -> DeviceMessage,
) {
    val from = snapshot?.at ?: Instant.DISTANT_PAST
    if (snapshot != null) restoreSnapshot(snapshot)
    log.replay(from, at).map(mutator).collect { applyEvent(it) }
}

/** Captured divergence point: snapshot at [at] and the exact storage [cursor]. */
public data class BranchPoint(
    public val at: Instant,
    public val baseSnapshot: DeviceSnapshot,
    public val cursor: EventCursor,
)

internal data class TimeWindowMutation(
    val timeWindow: ClosedRange<Instant>,
    val property: Name,
    val block: Meta.() -> Meta,
)

/**
 * Replays [log] up to [at] and captures a deterministic branch point.
 * Requires cursor replay; timestamp-only logs cannot identify a stable branch position.
 */
public suspend fun Reconstructible.branchAt(
    log: CursorEventLog,
    at: Instant,
    from: Instant = Instant.DISTANT_PAST,
): BranchPoint {
    var lastCursor: EventCursor? = null
    log.replayRecords(from, at).collect { record ->
        applyEvent(record.message)
        lastCursor = record.cursor
    }
    val snapshot = captureSnapshot(at)
    return BranchPoint(
        at = at,
        baseSnapshot = snapshot,
        cursor = checkNotNull(lastCursor) {
            "CursorEventLog has no event at or before $at in [$from, $at]; " +
                    "deterministic branch position is unavailable."
        },
    )
}

/**
 * Replays the canonical continuation after this [BranchPoint].
 * Returns a cold [Flow] to prevent memory exhaustion on large event logs.
 */
public fun BranchPoint.replayHistory(
    log: CursorEventLog,
): Flow<DeviceMessage> = log.replayFrom(cursor).map { it.message }

/**
 * Reverts to [branch]'s state, then applies [alternativeTimeline]. Final state = what
 * would have happened had history diverged at [branch].
 */
public suspend fun Reconstructible.whatIf(
    branch: BranchPoint,
    alternativeTimeline: Flow<DeviceMessage>,
    horizon: Instant = Instant.DISTANT_FUTURE,
) {
    restoreSnapshot(branch.baseSnapshot)
    alternativeTimeline
        .takeWhile { it.time <= horizon }
        .collect { applyEvent(it) }
}

/**
 * Receiver for the counterfactual DSL. Use [inject] to wholly replace an event at the
 * branch point, or [mutate] to patch a [PropertyChangedMessage]'s value in a type-safe
 * way.
 */
public class CounterfactualScope {
    internal val injections: MutableList<DeviceMessage> = mutableListOf()
    internal val mutations: MutableMap<Pair<Instant, Name>, Meta.() -> Meta> = mutableMapOf()
    internal val windowMutations: MutableList<TimeWindowMutation> = mutableListOf()
    internal val cursorReplacements: MutableMap<EventCursor, DeviceMessage> = mutableMapOf()
    internal val cursorMutations: MutableMap<Pair<EventCursor, Name>, Meta.() -> Meta> = mutableMapOf()

    /** Registers a complete event to inject at the branch point. */
    public fun inject(event: DeviceMessage) {
        injections += event
    }

    /**
     * Registers a field-level mutation: at [time] for [property], apply [block] to the
     * existing [PropertyChangedMessage]'s value [Meta]. Only [PropertyChangedMessage]s are
     * targeted; events of other types pass through unchanged.
     *
     * Timestamp targeting is convenient in notebooks and exploratory scripts, but it is
     * not a stable event identity after persistence round-trips that may truncate or
     * normalize timestamps. For deterministic replay over a [CursorEventLog], prefer
     * [mutate] with an [EventCursor].
     */
    public fun mutate(
        time: Instant,
        property: Name,
        block: Meta.() -> Meta,
    ) {
        val key = time to property
        require(key !in mutations) {
            "Duplicate counterfactual mutation for property '$property' at $time."
        }
        mutations[key] = block
    }

    /**
     * Registers a field-level mutation for events whose timestamp falls inside
     * [timeWindow]. Window targeting is useful after persistence round-trips that may
     * truncate timestamps. Cursor mutations and exact-time mutations are evaluated first;
     * overlapping windows are resolved in declaration order.
     */
    public fun mutate(
        timeWindow: ClosedRange<Instant>,
        property: Name,
        block: Meta.() -> Meta,
    ) {
        require(timeWindow.start <= timeWindow.endInclusive) {
            "Counterfactual mutation time window must not be empty: $timeWindow."
        }
        windowMutations += TimeWindowMutation(timeWindow, property, block)
    }

    /**
     * Replaces the exact event at [cursor]. Cursor targeting is stable across persistent
     * logs because cursors are assigned by the store, not derived from physical time.
     */
    public fun replace(cursor: EventCursor, event: DeviceMessage) {
        require(cursor !in cursorReplacements) {
            "Duplicate counterfactual replacement for cursor '$cursor'."
        }
        cursorReplacements[cursor] = event
    }

    /**
     * Mutates the [PropertyChangedMessage] at [cursor] when its property matches
     * [property]. Use this overload for deterministic branches over [CursorEventLog].
     */
    public fun mutate(
        cursor: EventCursor,
        property: Name,
        block: Meta.() -> Meta,
    ) {
        val key = cursor to property
        require(key !in cursorMutations) {
            "Duplicate counterfactual mutation for property '$property' at cursor $cursor."
        }
        cursorMutations[key] = block
    }
}

/**
 * DSL wrapper over the functional [counterfactual] API. Inside [body], call
 * `inject(message)` to substitute events, or `mutate(time, property) { ... }` to
 * patch property values.
 *
 * ```kotlin
 * reconstructible.counterfactualScope(eventLog, at = branch.at, snapshot = branch.baseSnapshot) {
 *     inject(PropertyChangedMessage(
 *         time = branch.at, property = "temperature".asName(), value = metaOf(250.0)
 *     ))
 *     mutate(time = branch.at, property = "pressure".asName()) {
 *         Meta { set("value", 105.0) }
 *     }
 * }
 * ```
 */
@ExperimentalTimeTravelApi
public suspend fun Reconstructible.counterfactualScope(
    log: DeviceEventLog,
    at: Instant,
    snapshot: DeviceSnapshot? = null,
    body: CounterfactualScope.() -> Unit,
) {
    val scope = CounterfactualScope().apply(body)
    require(scope.cursorReplacements.isEmpty() && scope.cursorMutations.isEmpty()) {
        "Cursor-targeted counterfactual operations require the CursorEventLog overload."
    }

    if (scope.injections.isEmpty() && scope.mutations.isEmpty() && scope.windowMutations.isEmpty()) {
        // No-op: just replay without mutation.
        counterfactual(log, at, snapshot) { it }
        return
    }

    val plan = scope.toCounterfactualPlan()

    counterfactual(log, at, snapshot) { event ->
        plan.apply(event, cursor = null)
    }
}

/**
 * Cursor-aware counterfactual DSL. Cursor-targeted replacements and mutations are
 * evaluated before timestamp-targeted convenience operations.
 */
@ExperimentalTimeTravelApi
public suspend fun Reconstructible.counterfactualScope(
    log: CursorEventLog,
    at: Instant,
    snapshot: DeviceSnapshot? = null,
    body: CounterfactualScope.() -> Unit,
) {
    val scope = CounterfactualScope().apply(body)
    if (
        scope.injections.isEmpty() &&
        scope.mutations.isEmpty() &&
        scope.windowMutations.isEmpty() &&
        scope.cursorReplacements.isEmpty() &&
        scope.cursorMutations.isEmpty()
    ) {
        counterfactual(log, at, snapshot) { it }
        return
    }

    val from = snapshot?.at ?: Instant.DISTANT_PAST
    if (snapshot != null) restoreSnapshot(snapshot)
    val plan = scope.toCounterfactualPlan()
    log.replayRecords(from, at).collect { record ->
        applyEvent(plan.apply(record.message, record.cursor))
    }
}

private data class CounterfactualPlan(
    val propertyInjections: Map<Pair<Instant, Name>, PropertyChangedMessage>,
    val genericInjections: Map<Pair<String, Instant>, DeviceMessage>,
    val cursorReplacements: Map<EventCursor, DeviceMessage>,
    val timeMutations: Map<Pair<Instant, Name>, Meta.() -> Meta>,
    val windowMutations: List<TimeWindowMutation>,
    val cursorMutations: Map<Pair<EventCursor, Name>, Meta.() -> Meta>,
) {
    fun apply(event: DeviceMessage, cursor: EventCursor?): DeviceMessage {
        if (cursor != null) {
            cursorReplacements[cursor]?.let { return it }
        }

        val injected = if (event is PropertyChangedMessage) {
            propertyInjections[event.time to event.property]
        } else {
            genericInjections[event.messageType to event.time]
        }
        if (injected != null) return injected

        if (event !is PropertyChangedMessage) return event
        if (cursor != null) {
            cursorMutations[cursor to event.property]?.let { block ->
                return event.copy(value = block(event.value))
            }
        }
        timeMutations[event.time to event.property]?.let { block ->
            return event.copy(value = block(event.value))
        }
        val windowBlock = windowMutations
            .firstOrNull { mutation -> mutation.property == event.property && event.time in mutation.timeWindow }
            ?.block
            ?: return event
        return event.copy(value = windowBlock(event.value))
    }
}

private fun CounterfactualScope.toCounterfactualPlan(): CounterfactualPlan =
    CounterfactualPlan(
        propertyInjections = injections
            .filterIsInstance<PropertyChangedMessage>()
            .associateUniqueBy("property injection") { it.time to it.property },
        genericInjections = injections
            .filterNot { it is PropertyChangedMessage }
            .associateUniqueBy("generic injection") { it.messageType to it.time },
        cursorReplacements = cursorReplacements.toMap(),
        timeMutations = mutations.toMap(),
        windowMutations = windowMutations.toList(),
        cursorMutations = cursorMutations.toMap(),
    )

private inline fun <T, K> Iterable<T>.associateUniqueBy(
    kind: String,
    keySelector: (T) -> K,
): Map<K, T> {
    val result = LinkedHashMap<K, T>()
    for (element in this) {
        val key = keySelector(element)
        require(result.put(key, element) == null) {
            "Duplicate counterfactual $kind for key '$key'. Use event cursors or a single deterministic replacement."
        }
    }
    return result
}
