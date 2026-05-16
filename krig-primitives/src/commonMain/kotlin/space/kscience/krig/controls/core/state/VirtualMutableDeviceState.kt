package space.kscience.krig.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.observed

/**
 * A [MutableDeviceState] that holds its value in memory with no corresponding physical state.
 * Used for logical state management within a composite device.
 * Initialized with a non-null value.
 *
 * @param initialValue The non-null initial value.
 */
public class VirtualMutableDeviceState<T>(initialValue: T) : MutableDeviceState<T> {

    private val flow = MutableStateFlow<ObservedValue<T?>>(observed(initialValue))

    override val stateFlow: StateFlow<ObservedValue<T?>> get() = flow
    override val stateValue: ObservedValue<T?> get() = flow.value

    override suspend fun update(value: T, quality: DataQuality) {
        flow.value = observed(value, quality = quality)
    }

    override suspend fun updateState(stateValue: ObservedValue<T?>) {
        flow.value = stateValue
    }

    override fun toString(): String = "VirtualMutableDeviceState(value=$value)"
}
