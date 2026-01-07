package space.kscience.controls.core.runtime

import space.kscience.controls.core.context.CompositeContext
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.meta.MutableDevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.state.DeviceState
import space.kscience.controls.core.legacy_alpha_2.state.MutableDeviceState
import space.kscience.dataforge.names.Name
import kotlin.time.Clock

/**
 * A capability interface for any device that acts as a container for other devices
 * and manages a graph of reactive states.
 *
 * This provides a clean, runtime-agnostic way for components like property delegates
 * to resolve children and states without depending on a specific implementation like `CompositeDevice`.
 * It is a key part of the dependency inversion between the DSL and Runtime modules.
 */
public interface CompositeDeviceContext : StateContainer, CompositeContext {

    /**
     * The clock associated with this device context, typically inherited from the host device.
     */
    public val clock: Clock

    /**
     * Retrieves a direct child device by its local name.
     *
     * @param name The simple (single-token) name of the child device.
     * @return The [Device] instance if found, otherwise `null`.
     */
    public fun getChildDevice(name: Name): Device?

    /**
     * Gets a reactive [DeviceState] wrapper for a given read-only property specification.
     * The implementation is responsible for creating and caching this state.
     */
    public fun <T> getState(spec: DevicePropertySpec<*, T>): DeviceState<T>

    /**
     * Gets a reactive [MutableDeviceState] wrapper for a given mutable property specification.
     * The implementation is responsible for creating and caching this state, ensuring type safety.
     *
     * @param spec The full specification of the property, which carries type information.
     * @return The [MutableDeviceState] instance.
     */
    public fun <T> getMutableState(spec: MutableDevicePropertySpec<*, T>): MutableDeviceState<T>
}