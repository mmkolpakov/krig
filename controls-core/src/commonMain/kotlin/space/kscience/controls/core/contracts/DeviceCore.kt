package space.kscience.controls.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.core.contracts.Device.Companion.ACTION_TARGET
import space.kscience.controls.core.contracts.Device.Companion.CHILD_DEVICE_TARGET
import space.kscience.controls.core.contracts.Device.Companion.PROPERTY_TARGET
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Provider
import kotlin.time.Clock

/**
 * A contract for a device that exposes properties.
 */
public interface PropertyDevice {
    /**
     * A collection of descriptors for all properties supported by this device.
     */
    public val propertyDescriptors: Collection<PropertyDescriptor>

    /**
     * Reads the physical value of a property. This operation may involve I/O and is therefore suspendable.
     * Upon successful read, it should also update the logical state and emit a [PropertyChangedMessage].
     *
     * @param propertyName The name of the property to read.
     * @param context The execution context, providing security and tracing information.
     * @return The value of the property as a [Meta] object.
     * @throws space.kscience.controls.core.faults.CompositeHubException if the property does not exist or a read error occurs.
     */
    @InternalControlsApi
    public suspend fun readProperty(propertyName: Name, context: ExecutionContext = ExecutionContext(SystemPrincipal)): Meta

    /**
     * Writes a new value to a mutable property. This is a suspendable operation.
     *
     * @param propertyName The name of the property to write.
     * @param value The new value to set.
     * @param context The execution context, providing security and tracing information.
     * @throws space.kscience.controls.core.faults.CompositeHubException if the property is not mutable or a write error occurs.
     */
    @InternalControlsApi
    public suspend fun writeProperty(propertyName: Name, value: Meta, context: ExecutionContext = ExecutionContext(SystemPrincipal))
}

/**
 * A contract for a device that exposes actions.
 */
public interface ActionDevice {
    /**
     * A collection of descriptors for all actions supported by this device.
     */
    public val actionDescriptors: Collection<ActionDescriptor>

    /**
     * Executes a device-specific action.
     *
     * @param actionName The name of the action to execute.
     * @param argument An optional [Meta] object containing arguments for the action.
     * @param context The execution context, providing security and tracing information.
     * @return An optional [Meta] object representing the result of the action. Returns `null` if the action does not produce a result.
     * @throws space.kscience.controls.core.faults.CompositeHubException if the action is not supported or fails during execution.
     */
    @InternalControlsApi
    public suspend fun execute(
        actionName: Name,
        argument: Meta? = null,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?
}

/**
 * A general interface describing a managed Device. A [Device] instance serves as a [CoroutineScope]
 * for all its internal operations. It also acts as a [Provider] for its children, properties, and actions,
 * enabling seamless integration with the DataForge ecosystem.
 *
 * The device's lifecycle is formally defined and managed by a Finite State Machine (FSM).
 * Commands to change the lifecycle state (e.g., start, stop) are sent as events to this FSM,
 * typically by a [space.kscience.controls.composite.old.contracts.CompositeDeviceHub]. The current state of the lifecycle is exposed reactively
 * via the [lifecycleState] property from the [ManagedComponent] interface.
 *
 * ### Communication Model: Hybrid RPC and Event Stream
 *
 * This interface employs a hybrid communication old that combines the simplicity of RPC-style method calls
 * with the observability of an event stream, aligning with the Command Query Responsibility Segregation (CQRS) pattern.
 *
 * - **Commands and Queries (RPC-style):** The methods `readProperty`, `writeProperty`, and `execute` define a clear,
 *   synchronous-looking (but asynchronous) contract for interactions. This provides a straightforward API for developers.
 *   **Important:** While these look like direct method calls, a compliant runtime
 *   **should** translate these calls into internal, serializable request messages. This ensures that in a
 *   distributed system, all interactions can be transmitted over a network and audited.
 *
 * - **Notifications (Event Stream):** The `messageFlow` property provides a one-way, hot [SharedFlow] of [DeviceMessage]s.
 *   This flow broadcasts asynchronous notifications about state changes, such as property updates, lifecycle events,
 *   and errors. It is not used for requests or direct command responses.
 *
 */
public interface Device : ManagedComponent, CoroutineScope, PropertyDevice, ActionDevice, Provider {
    /**
     * Companion object holding stable identifiers for the capability.
     */
    public companion object {
        /**
         * The unique, fully-qualified name for the [Device] capability.
         * Used for feature detection and serialization.
         */
        public const val CAPABILITY: String = "space.kscience.controls.core.contracts.Device"

        /** DataForge provider target for accessing child devices. */
        public const val CHILD_DEVICE_TARGET: String = "child"
        /** DataForge provider target for accessing property descriptors. */
        public const val PROPERTY_TARGET: String = PropertyDescriptor.TYPE
        /** DataForge provider target for accessing action descriptors. */
        public const val ACTION_TARGET: String = ActionDescriptor.TYPE
    }

    /**
     * The local name of this device instance within its parent hub.
     * This name is used for addressing within the hub's scope.
     */
    public val name: Name

    /**
     * The configuration meta for the device. This is an [ObservableMeta], meaning that the device
     * can react to configuration changes in real-time without requiring a restart. The runtime
     * is responsible for constructing this meta by layering blueprint, child, and attachment configurations.
     */
    public val meta: ObservableMeta

    /**
     * A hot flow of messages originating from this device. This includes property changes,
     * action results, log messages, and lifecycle events. The flow is shared and may have a replay cache.
     */
    public val messageFlow: SharedFlow<DeviceMessage>

    /**
     * The clock associated with this device. It may be a system clock, a virtual clock for simulations,
     * or a compressed-time clock. This clock **must** be used as the source for all timestamps
     * in [StateValue] updates originating from this device to ensure time consistency.
     */
    public val clock: Clock

    /**
     * Provides content for DataForge's [Provider] mechanism. A runtime implementation of [Device]
     * **must** override this method to expose its properties, actions, and child devices for introspection.
     * The default implementation returns an empty map.
     *
     * Standard targets:
     * - [PROPERTY_TARGET]: Exposes [PropertyDescriptor]s.
     * - [ACTION_TARGET]: Exposes [ActionDescriptor]s.
     * - [CHILD_DEVICE_TARGET]: Exposes child [Device]s.
     *
     * @param target A string identifier for the type of content being requested.
     * @return A map of named content items.
     */
    override fun content(target: String): Map<Name, Any> = emptyMap()

    /**
     * Opens a direct, typed accessor for a property (Data Plane).
     *
     * This method attempts to provide the most efficient path to the hardware/driver, avoiding
     * the overhead of [Meta] serialization where possible.
     *
     * If the driver does not support a specialized accessor for the given property,
     * a fallback implementation wrapping [readProperty] and [writeProperty] is returned.
     *
     * @param spec The typed specification of the property.
     * @return A [PropertyAccessor] for reading and writing values.
     */
    @OptIn(InternalControlsApi::class)
    public fun <D : Device, T> openPropertyAccessor(spec: DevicePropertySpec<D, T>): PropertyAccessor<T> {
        return object : GenericAccessor<T> {
            override val propertySpec: DevicePropertySpec<*, T> = spec

            override suspend fun read(): T {
                val meta = readProperty(spec.name)
                return spec.converter.read(meta)
            }

            override suspend fun write(value: T) {
                val meta = spec.converter.convert(value)
                writeProperty(spec.name, meta)
            }
        }
    }

    /**
     * Opens a direct, typed accessor for an action (Data Plane).
     *
     * This method attempts to provide the most efficient path to execute the action logic,
     * avoiding the overhead of [Meta] serialization where possible.
     *
     * If the driver does not support a specialized accessor, a fallback implementation
     * wrapping [execute] is returned.
     *
     * @param spec The typed specification of the action.
     * @return An [ActionAccessor] for invoking the action.
     */
    @OptIn(InternalControlsApi::class)
    public fun <D : Device, I, O> openActionAccessor(spec: DeviceActionSpec<D, I, O>): ActionAccessor<I, O> {
        return object : TypedActionAccessor<I, O> {
            override val actionSpec: DeviceActionSpec<*, I, O> = spec

            override suspend fun invoke(input: I): O {
                val inputMeta = spec.inputConverter.convert(input)
                val resultMeta = execute(spec.name, inputMeta)
                return spec.outputConverter.read(resultMeta ?: Meta.EMPTY)
            }
        }
    }
}