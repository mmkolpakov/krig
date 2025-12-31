package space.kscience.controls.fsm.guards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.data.StateValue
import space.kscience.controls.core.features.GuardSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * An interface for a reusable, stateful predicate that operates on a history of property values.
 * Implementations of this interface can be discovered and used by the runtime to evaluate statistical guards.
 *
 * @param T The type of the property value.
 */
public interface HistoricalPredicate<in T> {
    /**
     * The unique identifier for this predicate logic. Used by `ValueChangeGuardSpec` to reference it.
     */
    public val id: String

    /**
     * Evaluates the predicate based on a list of historical state values.
     *
     * @param history A list of [StateValue]s, ordered from oldest to newest.
     * @return `true` if the condition is met, `false` otherwise.
     */
    public fun evaluate(history: List<StateValue<T>>): Boolean
}

/**
 * A guard that triggers based on a historical analysis of a property's value.
 * It uses a pluggable `HistoricalPredicate` to perform the analysis.
 *
 * @property propertyName The name of the property to monitor.
 * @property windowSize The number of historical values to maintain for the predicate.
 * @property predicateId The unique identifier of the `HistoricalPredicate` logic to apply.
 * @property predicateMeta Optional configuration metadata to be passed to the predicate's factory.
 * @property postEventSerialName The fully qualified serial name of the event class to post when the predicate is fulfilled.
 * @property eventMeta Optional metadata for constructing the event instance.
 * @property onlyInStates An optional set of state names, restricting the guard's activity.
 */
@Serializable
@SerialName("guard.valueChange")
public data class ValueChangeGuardSpec(
    val propertyName: Name,
    val windowSize: Int,
    val predicateId: String,
    val predicateMeta: Meta = Meta.EMPTY,
    val postEventSerialName: String,
    val eventMeta: Meta = Meta.EMPTY,
    val onlyInStates: Set<String> = emptySet(),
) : GuardSpec