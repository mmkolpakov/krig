package space.kscience.controls.fsm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.IState
import ru.nsk.kstatemachine.statemachine.StateMachine
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceDriver
import kotlin.time.Duration

/**
 * Provides a type-safe context for defining the lifecycle FSM within a [DeviceBlueprint].
 * It offers access to the device instance, its children, and the driver.
 *
 * This context serves as the bridge between the declarative FSM definition and the concrete device instance,
 * enabling reactive logic to be defined directly within lifecycle states.
 *
 * @param D The type of the device this context belongs to.
 */
public interface LifecycleContext<D : Device> {
    /**
     * The device instance on which the lifecycle FSM is operating.
     */
    public val device: D

    /**
     * The driver responsible for creating and managing the lifecycle hooks of the device.
     * The type uses an `in` projection because the driver is a "consumer" of the device type `D`
     * (it knows how to handle `D` or any of its supertypes).
     */
    public val driver: DeviceDriver<in D>

    /**
     * The dedicated [kotlinx.coroutines.CoroutineScope] for this device instance.
     * All long-running operations and listeners related to the device's logic should be launched in this scope.
     */
    public val deviceScope: CoroutineScope

    /**
     * Retrieves an initialized child device by its blueprint.
     * This function is safe to call during FSM configuration to set up interactions between parent and child.
     */
    public suspend fun <CD : Device> child(blueprint: DeviceBlueprint<CD>): CD

    /**
     * Programmatically posts a new [ru.nsk.kstatemachine.event.Event] to the device's lifecycle FSM.
     * This allows for creating custom, logic-driven workflows within the state machine.
     *
     * @param event The lifecycle event to post to the FSM.
     */
    public suspend fun postEvent(event: Event)

    /**
     * Programmatically posts a new [Event] to the device's operational FSM, if it exists.
     * This is the primary mechanism for actions and internal logic to interact with the operational state.
     *
     * @param event The operational event to post.
     */
    public suspend fun postOperationalEvent(event: Event)

    /**
     * The operational Finite State Machine of the device, if it exists.
     * This provides a way to interact with the operational FSM from within the lifecycle FSM.
     */
    public val operationalFsm: StateMachine?

    /**
     * A [kotlinx.coroutines.flow.StateFlow] representing the current active state of the operational FSM.
     * Returns `null` if no operational FSM is defined for the device.
     * This allows lifecycle states to react to operational state changes.
     */
    public fun operationalFsmState(): StateFlow<IState>?

    /**
     * Exports a diagram of the specified FSM to a PlantUML string representation.
     * This is intended for introspection and debugging and requires a runtime implementation.
     *
     * @param isLifeCycle `true` to export the lifecycle FSM, `false` for the operational FSM.
     * @return A PlantUML diagram string, or `null` if the requested FSM does not exist.
     */
    public suspend fun exportFsmDiagram(isLifeCycle: Boolean): String?

    /**
     * Instructs the runtime to start a periodic timer associated with this device.
     * The timer will post [space.kscience.controls.fsm.events.TimerTickEvent]s to the device's lifecycle FSM. The runtime is responsible
     * for managing the timer's lifecycle, ensuring it is active only when the device is.
     *
     * This method is idempotent; calling it multiple times with the same name will not create multiple timers.
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