package space.kscience.controls.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import space.kscience.controls.core.state.DeviceState
import space.kscience.controls.core.state.MutableDeviceState
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.MetaConverter

/**
 * An abstract element representing a piece of logic or state within a [StateContainer].
 * Used for introspection and visualization of the device's internal structure.
 */
public sealed interface ConstructorElement

/**
 * A [ConstructorElement] representing a single, independent state.
 */
public class StateConstructorElement<T>(public val state: DeviceState<T>) : ConstructorElement

/**
 * A [ConstructorElement] for an internal, logical state created via the `stateful` delegate.
 * It holds additional information required for persistence.
 * @property propertyName The name of the property backing this state.
 * @property state The [MutableDeviceState] instance.
 * @property converter A [MetaConverter] for serialization.
 */
public class StatefulDelegateElement<T>(
    public val propertyName: String,
    public val state: MutableDeviceState<T>,
    public val converter: MetaConverter<T>,
) : ConstructorElement

/**
 * A [ConstructorElement] representing a connection or data flow between states.
 * @param reads The set of states that are inputs to this connection.
 * @param writes The set of states that are outputs of this connection.
 */
public class ConnectionConstructorElement(
    public val reads: Collection<DeviceState<*>>,
    public val writes: Collection<DeviceState<*>>,
) : ConstructorElement

/**
 * A contract for any component that can host and manage a graph of interconnected [DeviceState]s.
 * It provides a [CoroutineScope] for managing the lifecycle of reactive jobs.
 */
public interface StateContainer : ContextAware, CoroutineScope {
    /**
     * A collection of all constructor elements within this container, allowing for introspection.
     */
    public val constructorElements: Set<ConstructorElement>

    /**
     * Registers a [ConstructorElement] with this container for tracking.
     */
    public fun registerElement(constructorElement: ConstructorElement)

    /**
     * Unregisters a previously registered [ConstructorElement].
     */
    public fun unregisterElement(constructorElement: ConstructorElement)
}

/**
 * Subscribes to changes in a [DeviceState] and executes an action for each new value.
 * The action is not triggered for `null` values.
 *
 * @param T The type of the state's value.
 * @param container The [StateContainer] scope in which to launch the subscription job.
 * @param writes A collection of states that are modified by this action, for introspection.
 * @param reads A collection of states that are read by this action, for introspection.
 * @param onChange The suspendable action to perform on each new non-null value.
 * @return The [Job] managing this subscription. Cancelling the job will stop the subscription.
 */
public fun <T> DeviceState<T>.onNext(
    container: StateContainer,
    writes: Collection<DeviceState<*>> = emptySet(),
    reads: Collection<DeviceState<*>> = emptySet(),
    onChange: suspend (T) -> Unit,
): Job {
    val element = ConnectionConstructorElement(reads + this, writes)
    container.registerElement(element)
    return valueFlow.filterNotNull().onEach(onChange).launchIn(container).apply {
        invokeOnCompletion { container.unregisterElement(element) }
    }
}

/**
 * Subscribes to changes in a [DeviceState] and executes an action, providing both the previous and new values.
 * The action is only triggered if the new value is different from the previous one. Null values are included in comparisons.
 *
 * @param container The [StateContainer] scope in which to launch the subscription job.
 * @param onChange The suspendable action to perform, receiving the previous and new state values.
 * @return The [Job] managing this subscription.
 */
public fun <T> DeviceState<T>.onChange(
    container: StateContainer,
    writes: Collection<DeviceState<*>> = emptySet(),
    reads: Collection<DeviceState<*>> = emptySet(),
    onChange: suspend (prev: T?, next: T?) -> Unit,
): Job {
    val element = ConnectionConstructorElement(reads + this, writes)
    container.registerElement(element)
    return valueFlow.distinctUntilChanged().zip(valueFlow.drop(1)) { prev, next ->
        prev to next
    }.onEach { (prev, next) ->
        onChange(prev, next)
    }.launchIn(container).apply {
        invokeOnCompletion { container.unregisterElement(element) }
    }
}

/**
 * Binds the value of a [sourceState] to a [targetState]. Any update to the source will be
 * propagated to the target. This creates a one-way data flow.
 *
 * @return The [Job] managing the binding.
 */
public fun <T> StateContainer.bindState(
    sourceState: DeviceState<T>,
    targetState: MutableDeviceState<T>,
): Job {
    val element = ConnectionConstructorElement(setOf(sourceState), setOf(targetState))
    registerElement(element)

    return launch {
        // Initialize with the current state value
        targetState.updateState(sourceState.stateValue)
        // Propagate subsequent changes
        sourceState.stateFlow.collect {
            targetState.updateState(it)
        }
    }.apply {
        invokeOnCompletion { unregisterElement(element) }
    }
}