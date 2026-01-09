package space.kscience.controls.core.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.*
import ru.nsk.kstatemachine.statemachine.StateMachine
import ru.nsk.kstatemachine.statemachine.createStateMachine
import ru.nsk.kstatemachine.statemachine.onTransitionComplete
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.core.InternalControlsApi
import space.kscience.dataforge.context.Logger
import space.kscience.dataforge.context.error

/**
 * Internal events triggering lifecycle transitions.
 */
@InternalControlsApi
public sealed interface LifecycleEvent : Event {
    /** Request to start the device. */
    public data object Start : LifecycleEvent
    /** Request to stop the device. */
    public data object Stop : LifecycleEvent
    /** Signal that a failure occurred (exception). */
    public data class Fail(val error: Throwable) : LifecycleEvent
    /** Force kill signal (emergency shutdown). */
    public data object Kill : LifecycleEvent
    /** Internal signal indicating successful completion of an async hook (like connect). */
    public data object Success : LifecycleEvent
}

/**
 * Definitions of Device Container states.
 * This separates the "Infrastructure Lifecycle" (Driver/Resources) from "Business Lifecycle" (Calibration, etc).
 */
@InternalControlsApi
public sealed class DeviceFsmState(name: String) : DefaultState(name) {
    public object Created : DeviceFsmState("Created")
    public object Starting : DeviceFsmState("Starting")
    public object Running : DeviceFsmState("Running")
    public object Stopping : DeviceFsmState("Stopping")
    public object Stopped : DeviceFsmState("Stopped")
    public object Failed : DeviceFsmState("Failed")
    public object Destroyed : DefaultFinalState("Destroyed")
}

/**
 * A wrapper around KStateMachine to manage the lifecycle of a [DeviceEntity].
 *
 * **Architectural Note:**
 * This FSM manages the *Technical Lifecycle* (Resource Allocation, Driver Connection, Actor Liveness).
 * It guarantees that the device driver is only accessed when the device is in specific states.
 *
 * @param scope The coroutine scope for the FSM. This scope must be active.
 * @param scopeLogger Device-scoped logger.
 * @param onStartHook Logic to execute during startup (Driver connect, Capability start).
 * @param onStopHook Logic to execute during shutdown (Driver close, Capability stop).
 */
@InternalControlsApi
public class DeviceLifecycleFsm(
    private val scope: CoroutineScope,
    private val scopeLogger: Logger,
    private val onStartHook: suspend () -> Unit,
    private val onStopHook: suspend () -> Unit
) {
    private val _stateFlow = MutableStateFlow(DeviceLifecycleState.Detached)

    /**
     * Public projection of the internal FSM state to the API enum.
     */
    public val stateFlow: StateFlow<DeviceLifecycleState> = _stateFlow.asStateFlow()

    private val fsmDeferred: Deferred<StateMachine> = scope.async {
        createStateMachine(
            scope = scope,
            name = "DeviceLifecycle",
            start = false
        ) {
            val created = addInitialState(DeviceFsmState.Created)
            val starting = addState(DeviceFsmState.Starting)
            val running = addState(DeviceFsmState.Running)
            val stopping = addState(DeviceFsmState.Stopping)
            val stopped = addState(DeviceFsmState.Stopped)
            val failed = addState(DeviceFsmState.Failed)
            val destroyed = addFinalState(DeviceFsmState.Destroyed)

            // --- Transitions ---

            created {
                transition<LifecycleEvent.Start> { targetState = starting }
                transition<LifecycleEvent.Kill> { targetState = destroyed }
            }

            starting {
                onEntry {
                    try {
                        onStartHook()
                        machine.processEvent(LifecycleEvent.Success)
                    } catch (e: Throwable) {
                        scopeLogger.error(e) { "Device startup failed" }
                        machine.processEvent(LifecycleEvent.Fail(e))
                    }
                }
                transition<LifecycleEvent.Success> { targetState = running }
                transition<LifecycleEvent.Fail> { targetState = failed }
                transition<LifecycleEvent.Stop> { targetState = stopping }
            }

            running {
                transition<LifecycleEvent.Stop> { targetState = stopping }
                transition<LifecycleEvent.Fail> { targetState = failed }
                transition<LifecycleEvent.Kill> { targetState = destroyed }
            }

            stopping {
                onEntry {
                    try {
                        onStopHook()
                        machine.processEvent(LifecycleEvent.Success)
                    } catch (e: Throwable) {
                        scopeLogger.error(e) { "Device shutdown failed" }
                        machine.processEvent(LifecycleEvent.Fail(e))
                    }
                }
                transition<LifecycleEvent.Success> { targetState = stopped }
                transition<LifecycleEvent.Fail> { targetState = failed }
            }

            stopped {
                transition<LifecycleEvent.Start> { targetState = starting }
                transition<LifecycleEvent.Kill> { targetState = destroyed }
            }

            failed {
                transition<LifecycleEvent.Start> { targetState = starting }
                transition<LifecycleEvent.Kill> { targetState = destroyed }
            }

            // --- State Projection ---

            onTransitionComplete { activeStates, _ ->
                val active = activeStates.firstOrNull() ?: return@onTransitionComplete
                val publicState = when (active) {
                    is DeviceFsmState.Created -> DeviceLifecycleState.Detached
                    is DeviceFsmState.Starting -> DeviceLifecycleState.Starting
                    is DeviceFsmState.Running -> DeviceLifecycleState.Running
                    is DeviceFsmState.Stopping -> DeviceLifecycleState.Stopping
                    is DeviceFsmState.Stopped -> DeviceLifecycleState.Stopped
                    is DeviceFsmState.Failed -> DeviceLifecycleState.Failed
                    is DeviceFsmState.Destroyed -> DeviceLifecycleState.Detached
                    else -> DeviceLifecycleState.Failed
                }
                _stateFlow.value = publicState
            }
        }
    }
    /**
     * Starts the FSM. Must be called once.
     */
    public suspend fun start() {
        val fsm = fsmDeferred.await()
        if (!fsm.isRunning) {
            fsm.start()
        }
    }

    /**
     * Dispatches a lifecycle event.
     */
    internal suspend fun dispatch(event: LifecycleEvent) {
        fsmDeferred.await().processEvent(event)
    }
}