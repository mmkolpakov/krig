package space.kscience.controls.composite.dsl.lifecycle

import ru.nsk.kstatemachine.state.IState
import ru.nsk.kstatemachine.state.onEntry
import ru.nsk.kstatemachine.state.onExit
import ru.nsk.kstatemachine.state.transition
import ru.nsk.kstatemachine.transition.onTriggered
import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.fsm.events.TimerTickEvent
import kotlin.time.Duration

/**
 * Registers a periodic action to be executed while the FSM is in the given [state].
 * This is a high-level DSL function that serves as syntactic sugar over KStateMachine's `onEntry`, `transition`,
 * and `onExit` hooks, automating the lifecycle management of a timer.
 *
 * The timer is automatically started when the FSM enters the specified [state] and stopped when it exits,
 * preventing resource leaks and ensuring that periodic logic only runs when it's supposed to.
 *
 * Example:
 * ```
 * standardLifecycle {
 *     state(running) {
 *         // This timer will only be active when the device is in the 'running' state.
 *         onTimer(1.seconds) { dt ->
 *             val currentPos = read(position) ?: 0.0
 *             write(position, currentPos + 1.0 * dt.inWholeSeconds)
 *         }
 *     }
 * }
 * ```
 *
 * @param state The [IState] from the FSM builder in which this timer should be active.
 * @param tick The interval between executions of the [block].
 * @param initialDelay An optional delay before the first tick is fired.
 * @param name An optional, unique name for the timer. If not provided, a name is generated automatically.
 *             Providing a name is useful for debugging or for interacting with the timer from other parts of the FSM.
 * @param block The suspendable logic to execute on each tick. It receives the device instance as its receiver (`this`)
 *              and the actual duration since the last tick (`dt`) as an argument.
 */
@CompositeSpecDsl
public suspend fun <D : Device> StandardLifecycleBuilder<D>.onTimer(
    state: IState,
    tick: Duration,
    initialDelay: Duration = Duration.ZERO,
    name: String? = null,
    block: suspend D.(dt: Duration) -> Unit
) {
    // Generate a unique name for the timer to avoid collisions within the state.
    val timerName = name ?: "_internal_timer_${state.name}_${block.hashCode().toString(16)}"

    // Use the `state` extension to add logic to the specified state.
    state(state) {
        onEntry {
            // When the FSM enters this state, instruct the runtime to start the timer.
            this@onTimer.context.startTimer(timerName, tick, initialDelay)
        }

        // Define a target-less transition that listens for TimerTickEvent.
        transition<TimerTickEvent> {
            // The guard ensures that this transition only triggers for our specific timer.
            guard = { event.timerName == timerName }
            // The onTriggered block executes the user-provided logic.
            onTriggered {
                this@onTimer.device.block(it.event.dt)
            }
        }

        onExit {
            // When the FSM exits this state, instruct the runtime to stop the timer, preventing resource leaks.
            this@onTimer.context.stopTimer(timerName)
        }
    }
}