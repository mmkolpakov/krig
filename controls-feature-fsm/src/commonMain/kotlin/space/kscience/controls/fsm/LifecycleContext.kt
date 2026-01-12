package space.kscience.controls.fsm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.IState
import ru.nsk.kstatemachine.statemachine.StateMachine
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.core.contracts.DeviceConnection
import kotlin.time.Duration

/**
 * Provides a type-safe context for defining the lifecycle FSM within a [DeviceBlueprint].
 *
 * This context bridges the declarative FSM definition (using KStateMachine DSL) with the
 * concrete runtime components of the device. It allows the FSM states to interact with
 * the device actor and the underlying hardware connection.
 *
 * @param D The type of the device this context belongs to.
 */
public interface LifecycleContext<D : Device> {
    /**
     * The device instance on which the lifecycle FSM is operating.
     */
    public val device: D

    /**
     * The active connection to the hardware or protocol adapter.
     *
     * The lifecycle FSM uses this to perform low-level initialization sequences (e.g., sending
     * handshake bytes in `onStart`) or resource cleanup (e.g., closing sessions in `onStop`)
     * that bypass the high-level logical properties.
     */
    public val connection: DeviceConnection

    /**
     * The dedicated [CoroutineScope] for this device instance.
     * All background jobs (like polling loops or keep-alives) started by the lifecycle
     * must be launched in this scope to ensure proper cancellation when the device is destroyed.
     */
    public val deviceScope: CoroutineScope

    /**
     * Retrieves an initialized child device by its blueprint.
     * This function is used to coordinate the lifecycle of composite devices (e.g., starting children).
     */
    public suspend fun <CD : Device> child(blueprint: DeviceBlueprint<CD>): CD

    /**
     * Programmatically posts a new [Event] to the device's lifecycle FSM.
     * This allows for creating internal workflows and triggers within the state machine.
     *
     * @param event The lifecycle event to post.
     */
    public suspend fun postEvent(event: Event)

    /**
     * Programmatically posts a new [Event] to the device's operational FSM, if it exists.
     * This is the primary mechanism for the Lifecycle FSM to drive the Business Logic FSM
     * (e.g., transitioning Operational FSM to "Idle" after Lifecycle FSM reaches "Running").
     *
     * @param event The operational event to post.
     */
    public suspend fun postOperationalEvent(event: Event)

    /**
     * The operational Finite State Machine of the device, if one is defined.
     * Accessing this allows the lifecycle to inspect the structure of the business logic.
     */
    public val operationalFsm: StateMachine?

    /**
     * A [StateFlow] representing the current active state of the operational FSM.
     * Returns `null` if no operational FSM is defined.
     * This allows lifecycle states to reactively wait for specific operational states (e.g., waiting for "Idle" before stopping).
     */
    public fun operationalFsmState(): StateFlow<IState>?

    /**
     * Exports a diagram of the specified FSM (PlantUML/Mermaid).
     * Intended for introspection and debugging tools.
     *
     * @param isLifeCycle `true` to export the lifecycle FSM, `false` for the operational FSM.
     * @return A diagram string, or `null` if the requested FSM does not exist.
     */
    public suspend fun exportFsmDiagram(isLifeCycle: Boolean): String?

    /**
     * Instructs the runtime to start a periodic timer associated with this device.
     * The timer will post [space.kscience.controls.fsm.events.TimerTickEvent]s to the device's lifecycle FSM.
     *
     * @param name A unique name for the timer within the device's scope.
     * @param tick The interval between ticks.
     * @param initialDelay An optional delay before the first tick.
     */
    public fun startTimer(name: String, tick: Duration, initialDelay: Duration = Duration.Companion.ZERO)

    /**
     * Instructs the runtime to stop and remove a previously started timer.
     *
     * @param name The unique name of the timer to stop.
     */
    public fun stopTimer(name: String)
}