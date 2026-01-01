package space.kscience.controls.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import space.kscience.controls.core.data.Quality
import space.kscience.controls.core.data.StateValue
import space.kscience.controls.core.data.okState
import kotlin.concurrent.Volatile

/**
 * A [MutableDeviceState] that can be bound to another [DeviceState] at a later time.
 * Before binding, it holds an initial value. After binding, it acts as a proxy to the source state.
 * This is useful for resolving circular dependencies in complex device graphs, especially in simulations.
 *
 * @param T The type of the state value.
 * @param initialStateValue The initial state, including value, quality, and timestamp, before binding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class LateBindableDeviceState<T>(initialStateValue: StateValue<T?>) : MutableDeviceState<T> {
    private val sourceState = CompletableDeferred<DeviceState<T>>()

    @Volatile
    private var _stateValue: StateValue<T?> = initialStateValue

    override val stateValue: StateValue<T?>
        get() = if (sourceState.isCompleted) sourceState.getCompleted().stateValue else _stateValue

    override val stateFlow: Flow<StateValue<T?>> = flow {
        // Emit the initial state first.
        emit(_stateValue)
        // Await binding.
        val source = sourceState.await()
        // Emit the current state of the source immediately after binding.
        emit(source.stateValue)
        // Then, collect and emit all subsequent changes from the source.
        emitAll(source.stateFlow)
    }.distinctUntilChanged()


    /**
     * Binds this state to a source [DeviceState]. Can only be called once.
     * After binding, all reads will be delegated to the source, and writes will throw an exception
     * as the state becomes effectively read-only from the binding's perspective.
     *
     * @param source The [DeviceState] to bind to.
     */
    public fun bind(source: DeviceState<T>) {
        if (!sourceState.complete(source)) {
            error("LateBindableDeviceState is already bound.")
        }
    }

    public val isBound: Boolean get() = sourceState.isCompleted

    override suspend fun update(value: T) {
        if (isBound) error("Cannot directly update a bound LateBindableDeviceState. Writes should go to the source.")
        _stateValue = okState(value)
    }

    override suspend fun updateState(stateValue: StateValue<T?>) {
        if (isBound) error("Cannot directly update a bound LateBindableDeviceState. Writes should go to the source.")
        _stateValue = stateValue
    }

    override suspend fun updateQuality(quality: Quality, message: String?) {
        if (isBound) error("Cannot directly update a bound LateBindableDeviceState. Writes should go to the source.")
        _stateValue = _stateValue.copy(quality = quality)
    }
}