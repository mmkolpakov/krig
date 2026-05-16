package space.kscience.krig.concurrency

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Predicate-driven wait primitive over `MutableStateFlow<T>`. `StateFlow.first` semantics —
 * replay 1, conflated; fine for "is the door open?", wrong for event streams.
 */
public class Signal<T>(initial: T) {
    private val stateFlow = MutableStateFlow(initial)

    /** Current (conflated) value. */
    public val value: T get() = stateFlow.value

    /** Observable flow for reactive consumers. */
    public val flow: StateFlow<T> get() = stateFlow.asStateFlow()

    /** Updates the value, resuming every waiter whose predicate the new value satisfies. */
    public fun set(value: T) {
        stateFlow.value = value
    }

    /** Suspends until a value matches [predicate]; returns it. */
    public suspend fun waitUntil(predicate: (T) -> Boolean): T =
        stateFlow.first(predicate)
}
