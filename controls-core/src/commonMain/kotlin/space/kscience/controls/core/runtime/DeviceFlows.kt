package space.kscience.controls.core.runtime

import kotlinx.coroutines.flow.StateFlow
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.api.data.StateValue

/**
 * A context provided to the `logic` block of a device blueprint.
 * It serves as a type-safe accessor for the reactive [StateFlow] of device properties.
 */
public interface DeviceFlows {
    /**
     * Retrieves the [StateFlow] for a given property specification.
     * The runtime is responsible for creating and caching these flows.
     *
     * @param spec The [DevicePropertySpec] of the property.
     * @return A [StateFlow] that emits [StateValue] updates for the property.
     */
    public fun <T> flow(spec: DevicePropertySpec<*, T>): StateFlow<StateValue<T>>
}