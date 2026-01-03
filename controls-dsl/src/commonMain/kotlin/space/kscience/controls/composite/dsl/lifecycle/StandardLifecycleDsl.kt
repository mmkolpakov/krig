package space.kscience.controls.composite.dsl.lifecycle

import kotlinx.coroutines.launch
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.*
import ru.nsk.kstatemachine.state.invoke
import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import ru.nsk.kstatemachine.transition.TransitionParams
import ru.nsk.kstatemachine.transition.onTriggered
import space.kscience.controls.composite.dsl.CompositeSpecBuilder
import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.core.faults.CompositeHubException
import space.kscience.controls.core.faults.CompositeHubTransactionException
import space.kscience.controls.core.faults.DeviceLifecycleException
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.fsm.events.DeviceLifecycleEvent
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.fsm.LifecycleContext
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.context.error
import space.kscience.dataforge.context.logger
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A context for customizing a pre-built standard lifecycle FSM.
 * Provides access to the standard states to allow adding listeners or custom transitions.
 *
 * @property sm The [BuildingStateMachine] instance, providing access to the FSM builder DSL.
 * @property device The device instance this lifecycle belongs to.
 * @property context The [LifecycleContext] providing runtime services like event posting and access to the driver.
 * @property detached The initial state before the device is part of the runtime.
 * @property attaching The transient state for device instantiation.
 * @property stopped The state where the device is initialized and ready but not running.
 * @property starting The transient state for device startup logic.
 * @property running The state where the device is fully operational.
 * @property stopping The transient state for device shutdown logic.
 * @property failed The state representing an unrecoverable error.
 * @property detaching The transient state for device destruction.
 */
@OptIn(ExperimentalContracts::class)
@CompositeSpecDsl
public class StandardLifecycleBuilder<D : Device> internal constructor(
    public val sm: BuildingStateMachine,
    public val device: D,
    public val context: LifecycleContext<D>,
    public val detached: IState,
    public val attaching: IState,
    public val stopped: IState,
    public val starting: IState,
    public val running: IState,
    public val stopping: IState,
    public val failed: IState,
    public val detaching: IState,
) {
    /**
     * Allows adding custom logic to any of the standard lifecycle states.
     * This is the primary mechanism for customization.
     *
     * Example:
     * ```
     * standardLifecycle {
     *     state(running) {
     *         onEntry {
     *             device.logger.info { "Device is now running and fully operational." }
     *         }
     *     }
     *     // Add a new transition from a standard state
     *     running.transitionOn<MyCustomEvent> { targetState = myCustomState }
     * }
     * ```
     *
     * @param state The lifecycle state to configure (e.g., [running], [stopped]).
     * @param block A suspendable DSL block that will be applied to the state.
     */
    public suspend fun state(state: IState, block: suspend IState.() -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        state.block()
    }

    /**
     * A helper to execute a device action from within an FSM state's logic (e.g., onEntry).
     * This directly invokes the action's logic defined in its specification.
     * This function is provided for convenience and to ensure consistent behavior within the FSM context.
     *
     * @throws IllegalStateException if the action returns a result of an incompatible type.
     */
    public suspend fun <I, O> execute(spec: DeviceActionSpec<D, I, O>, input: I): O? {
        val result = spec.execute(device, input)
        @Suppress("UNCHECKED_CAST")
        return if (result == null) {
            null
        } else {
            (result as? O) ?: error(
                "Action '${spec.name}' returned an object of type ${result::class}, " +
                        "but a type compatible with the spec's output was expected."
            )
        }
    }

    /**
     * A helper to read a device property from within an FSM state's logic.
     *
     * @throws IllegalStateException if the property returns a value of an incompatible type.
     */
    public suspend fun <T> read(spec: DevicePropertySpec<D, T>): T? {
        val result = spec.read(device)
        @Suppress("UNCHECKED_CAST")
        return if (result == null) {
            null
        } else {
            (result as? T) ?: error(
                "Property '${spec.name}' returned an object of type ${result::class}, " +
                        "but type ${spec.valueType} was expected."
            )
        }
    }

    /**
     * A helper to write to a device property from within an FSM state's logic.
     */
    public suspend fun <T> write(spec: MutableDevicePropertySpec<D, T>, value: T) {
        spec.write(device, value)
    }

    /**
     * A helper to request a PlantUML diagram of the lifecycle FSM. This is useful for debugging
     * or logging the FSM structure from within its own logic.
     */
    public suspend fun exportLifecycleFsm(): String? = context.exportFsmDiagram(isLifeCycle = true)

    /**
     * A helper to request a PlantUML diagram of the operational FSM, if it exists.
     */
    public suspend fun exportOperationalFsm(): String? = context.exportFsmDiagram(isLifeCycle = false)
}

/**
 * Configures a standard device lifecycle state machine.
 * This function creates the common states (Detached, Stopped, Running, Failed, etc.) and transitions
 * between them based on standard [DeviceLifecycleEvent]s. The implementation uses KStateMachine.
 *
 * The provided [block] allows for customization of the standard states (e.g., adding `onEntry` logic)
 * or adding entirely new states and transitions to the FSM.
 *
 * @param D The type of the device.
 * @param block A DSL block with [StandardLifecycleBuilder] as its receiver for customization.
 */
public fun <D : Device> CompositeSpecBuilder<D>.standardLifecycle(
    block: suspend StandardLifecycleBuilder<D>.() -> Unit = {}
) {
    lifecycle { device, lifecycleContext ->
        // Define all standard lifecycle states using KStateMachine DSL
        val detached = initialState(DeviceLifecycleState.Detached.name)
        val attaching = state(DeviceLifecycleState.Attaching.name)
        val stopped = state(DeviceLifecycleState.Stopped.name)
        val starting = state(DeviceLifecycleState.Starting.name)
        val running = state(DeviceLifecycleState.Running.name)
        val stopping = state(DeviceLifecycleState.Stopping.name)
        val failed = state(DeviceLifecycleState.Failed.name)
        val detaching = state(DeviceLifecycleState.Detaching.name)

        // Define standard transitions between lifecycle states
        detached {
            transitionOn<DeviceLifecycleEvent.Attach> { targetState = { attaching } }
        }

        attaching {
            onEntry {
                lifecycleContext.deviceScope.launch {
                    try {
                        lifecycleContext.driver.onAttach(device)
                        lifecycleContext.postEvent(DeviceLifecycleEvent.Attach) // Signal completion
                    } catch (e: Exception) {
                        lifecycleContext.postEvent(
                            DeviceLifecycleEvent.Fail(
                                CompositeHubTransactionException("attach", device.name, e).toSerializableFailure()
                            )
                        )
                    }
                }
            }
            transition<DeviceLifecycleEvent.Attach> { targetState = stopped }
            transitionOn<DeviceLifecycleEvent.Fail> { targetState = { failed } }
        }

        stopped {
            transitionOn<DeviceLifecycleEvent.Start> { targetState = { starting } }
            transitionOn<DeviceLifecycleEvent.Detach> { targetState = { detaching } }
        }

        starting {
            onEntry {
                lifecycleContext.deviceScope.launch {
                    try {
                        lifecycleContext.driver.onStart(device)
                        lifecycleContext.postEvent(DeviceLifecycleEvent.Start) // Signal completion
                    } catch (e: Exception) {
                        lifecycleContext.postEvent(
                            DeviceLifecycleEvent.Fail(
                                DeviceLifecycleException(device.name, "Start failed", e).toSerializableFailure()
                            )
                        )
                    }
                }
            }
            transition<DeviceLifecycleEvent.Start> { targetState = running }
            transitionOn<DeviceLifecycleEvent.Fail> { targetState = { failed } }
            onExit {
                // afterStart is called only on successful start
                if (it.direction.targetState == running) {
                    lifecycleContext.deviceScope.launch { lifecycleContext.driver.afterStart(device) }
                }
            }
        }

        running {
            transitionOn<DeviceLifecycleEvent.Stop> { targetState = { stopping } }
            transitionOn<DeviceLifecycleEvent.Fail> { targetState = { failed } }
        }

        stopping {
            onEntry {
                lifecycleContext.deviceScope.launch {
                    try {
                        lifecycleContext.driver.onStop(device)
                        lifecycleContext.postEvent(DeviceLifecycleEvent.Stop) // Signal completion
                    } catch (e: Exception) {
                        // Failure during stop also moves to failed state
                        lifecycleContext.postEvent(
                            DeviceLifecycleEvent.Fail(
                                DeviceLifecycleException(device.name, "Stop failed", e).toSerializableFailure()
                            )
                        )
                    }
                }
            }
            transition<DeviceLifecycleEvent.Stop> { targetState = stopped }
            transitionOn<DeviceLifecycleEvent.Fail> { targetState = { failed } }
            onExit {
                if (it.direction.targetState == stopped) {
                    lifecycleContext.deviceScope.launch { lifecycleContext.driver.afterStop(device) }
                }
            }
        }

        failed {
            transitionOn<DeviceLifecycleEvent.Reset> {
                targetState = { stopped }
                onTriggered {
                    lifecycleContext.deviceScope.launch { lifecycleContext.driver.onReset(device) }
                }
            }
            onEntry {
                val error = it.eventOrNull<DeviceLifecycleEvent.Fail>()?.failure
                lifecycleContext.deviceScope.launch {
                    lifecycleContext.driver.onFail(
                        device,
                        error?.let { CompositeHubException(it.message) })
                }
            }
        }

        detaching {
            onEntry {
                lifecycleContext.deviceScope.launch {
                    try {
                        lifecycleContext.driver.onDetach(device)
                        lifecycleContext.postEvent(DeviceLifecycleEvent.Detach) // Signal completion
                    } catch (e: Exception) {
                        // Log error, but proceed to detached state anyway
                        device.context.logger.error(e) { "Error during detach for device ${device.name}" }
                        lifecycleContext.postEvent(DeviceLifecycleEvent.Detach)
                    }
                }
            }
            transition<DeviceLifecycleEvent.Detach> { targetState = detached }
        }

        // Apply user customizations via the builder
        val builder = StandardLifecycleBuilder(
            sm = this,
            device = device,
            context = lifecycleContext,
            detached = detached,
            attaching = attaching,
            stopped = stopped,
            starting = starting,
            running = running,
            stopping = stopping,
            failed = failed,
            detaching = detaching
        )
        builder.block()
    }
}

/**
 * Helper extension to safely get an event of a specific type from TransitionParams.
 */
private inline fun <reified E : Event> TransitionParams<*>.eventOrNull(): E? = event as? E