package space.kscience.controls.validation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.GuardSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * A guard that triggers after a boolean predicate remains true for a given duration.
 *
 * @property predicateName The name of the boolean property (a predicate) to monitor.
 * @property holdFor The duration for which the predicate must remain `true` before the event is posted.
 * @property postEventSerialName The fully qualified serial name of the event class to be posted.
 * @property eventMeta Optional metadata to be included when constructing the event instance.
 * @property onlyInStates An optional set of state names. If provided, the guard is only active when the
 *                        operational FSM is in one of these states.
 */
@Serializable
@SerialName("guard.timedPredicate")
public data class TimedPredicateGuardSpec(
    val predicateName: Name,
    val holdFor: Duration,
    val postEventSerialName: String,
    val eventMeta: Meta = Meta.EMPTY,
    val onlyInStates: Set<String> = emptySet()
) : GuardSpec