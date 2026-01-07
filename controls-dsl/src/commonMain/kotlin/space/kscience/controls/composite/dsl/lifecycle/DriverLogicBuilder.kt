package space.kscience.controls.composite.dsl.lifecycle

import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.meta.DeviceActionSpec
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.meta.MutableDevicePropertySpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A reusable fragment of a device's implementation logic.
 * The type parameter `D` is contravariant (`in`) to allow applying a fragment for a general
 * device contract to a builder for a more specific one.
 */
@CompositeSpecDsl
public fun interface DriverLogicFragment<in D : Device> {
    /**
     * Applies the fragment's logic to the given [DriverLogicBuilder].
     * @param builder The builder instance to which the fragment's logic should be applied.
     */
    public fun apply(builder: DriverLogicBuilder<out D>)
}

/**
 * A factory function to create a [DriverLogicFragment] with a type-safe DSL receiver.
 * This is the primary entry point for creating reusable logic fragments.
 *
 * @param D The device contract type for which the logic is defined.
 * @param block The DSL block where the logic is defined. The receiver (`this`) is `DriverLogicBuilder<D>`.
 * @return A new [DriverLogicFragment] instance.
 */
public fun <D : Device> driverLogicFragment(
    block: DriverLogicBuilder<D>.() -> Unit
): DriverLogicFragment<D> = DriverLogicFragment { builder ->
    @Suppress("UNCHECKED_CAST")
    (builder as DriverLogicBuilder<D>).block()
}

/**
 * A type-safe DSL builder for defining the implementation logic of a device's properties,
 * actions, and lifecycle hooks. This builder is used within the `driverLogic` block of a
 * `CompositeSpecBuilder` to connect the declarative API from a [DeviceSpecification] to
 * its concrete behavior.
 *
 * This builder ensures type safety by accepting [DevicePropertySpec] and [DeviceActionSpec]
 * instances as keys, allowing the compiler to verify the types of the provided logic blocks.
 *
 * @param D The device contract type for which the logic is being defined.
 * @property spec An instance of the `DeviceSpecification`. This is primarily used for
 *                type inference and ensuring consistency.
 */
@CompositeSpecDsl
public class DriverLogicBuilder<D : Device>(public val spec: DeviceSpecification<D>) {

    // Internal maps to store the logic blocks.
    internal val propertyReaders = mutableMapOf<Name, suspend D.() -> Any?>()
    internal val propertyWriters = mutableMapOf<Name, suspend D.(Any?) -> Unit>()
    internal val actionExecutors = mutableMapOf<Name, suspend D.(Meta?) -> Meta?>()

    // Internal holders for lifecycle logic blocks.
    internal var onAttachLogic: suspend D.() -> Unit = {}
    internal var onStartLogic: suspend D.() -> Unit = {}
    internal var afterStartLogic: suspend D.() -> Unit = {}
    internal var onStopLogic: suspend D.() -> Unit = {}
    internal var afterStopLogic: suspend D.() -> Unit = {}
    internal var onResetLogic: suspend D.() -> Unit = {}
    internal var onFailLogic: suspend D.(Throwable?) -> Unit = { _ -> }
    internal var onDetachLogic: suspend D.() -> Unit = {}

    /**
     * Defines the implementation for reading a specific property.
     *
     * @param spec The [DevicePropertySpec] of the property.
     * @param block A suspendable lambda that takes the device instance (`this`) and returns the property value.
     */
    public fun <T> onRead(spec: DevicePropertySpec<D, T>, block: suspend D.() -> T?) {
        propertyReaders[spec.name] = block
    }

    /**
     * Defines the implementation for writing to a specific mutable property.
     *
     * @param spec The [MutableDevicePropertySpec] of the property.
     * @param block A suspendable lambda that takes the device instance (`this`) and the new value.
     */
    public fun <T> onWrite(spec: MutableDevicePropertySpec<D, T>, block: suspend D.(T) -> Unit) {
        propertyWriters[spec.name] = { value ->
            @Suppress("UNCHECKED_CAST")
            block(value as T)
        }
    }

    /**
     * Defines the implementation for executing a specific action.
     *
     * @param spec The [DeviceActionSpec] of the action.
     * @param block A suspendable lambda that takes the device instance (`this`) and the action input, and returns the output.
     */
    public fun <I, O> onExecute(spec: DeviceActionSpec<D, I, O>, block: suspend D.(I) -> O?) {
        actionExecutors[spec.name] = { meta ->
            val input = spec.inputConverter.read(meta ?: Meta.EMPTY)
            val result = block(input)
            result?.let { spec.outputConverter.convert(it) }
        }
    }

    /**
     * Defines the logic to be executed when the device is attached.
     */
    public fun onAttach(block: suspend D.() -> Unit) {
        onAttachLogic = block
    }

    /**
     * Defines the logic to be executed when the device starts.
     */
    public fun onStart(block: suspend D.() -> Unit) {
        onStartLogic = block
    }

    /**
     * Defines the logic to be executed after the device has successfully started.
     */
    public fun afterStart(block: suspend D.() -> Unit) {
        afterStartLogic = block
    }

    /**
     * Defines the logic to be executed when the device stops.
     */
    public fun onStop(block: suspend D.() -> Unit) {
        onStopLogic = block
    }

    /**
     * Defines the logic to be executed after the device has successfully stopped.
     */
    public fun afterStop(block: suspend D.() -> Unit) {
        afterStopLogic = block
    }

    /**
     * Defines the logic to be executed when the device is reset from a failed state.
     */
    public fun onReset(block: suspend D.() -> Unit) {
        onResetLogic = block
    }

    /**
     * Defines the logic to be executed when the device enters a failed state.
     */
    public fun onFail(block: suspend D.(Throwable?) -> Unit) {
        onFailLogic = block
    }

    /**
     * Defines the logic to be executed when the device is detached.
     */
    public fun onDetach(block: suspend D.() -> Unit) {
        onDetachLogic = block
    }
}