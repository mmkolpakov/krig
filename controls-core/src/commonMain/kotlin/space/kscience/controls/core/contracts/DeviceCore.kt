package space.kscience.controls.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.messages.PropertyChangedMessage
import space.kscience.controls.api.spec.CoreDeviceSpec
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Provider
import kotlin.time.Clock

/**
 * A contract for a device that exposes properties via the **Control Plane**.
 * Operations here typically return [Meta] and are suitable for configuration, UI, and slow control.
 */
public interface PropertyDevice {
    /**
     * A collection of descriptors for all properties supported by this device.
     */
    public val propertyDescriptors: Collection<PropertyDescriptor>

    /**
     * Reads the physical value of a property. This operation may involve I/O and is therefore suspendable.
     * Upon successful read, the implementation should also update the logical state and emit a [PropertyChangedMessage].
     *
     * @param propertyName The name of the property to read.
     * @param context The execution context, providing security and tracing information.
     * @return The value of the property as a [Meta] object.
     */
    @InternalControlsApi
    public suspend fun readProperty(
        propertyName: Name,
        context: ExecutionContext = ExecutionContext(SystemPrincipal)
    ): Meta

    /**
     * Writes a new value to a mutable property.
     *
     * @param propertyName The name of the property to write.
     * @param value The new value to set.
     * @param context The execution context, providing security and tracing information.
     */
    @InternalControlsApi
    public suspend fun writeProperty(
        propertyName: Name,
        value: Meta,
        context: ExecutionContext = ExecutionContext(SystemPrincipal)
    )
}

/**
 * A contract for a device that exposes actions via the **Control Plane**.
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
     * @return An optional [Meta] object representing the result of the action.
     */
    @InternalControlsApi
    public suspend fun execute(
        actionName: Name,
        argument: Meta? = null,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?
}

/**
 * The fundamental interface describing a Managed Device.
 *
 * A [Device] is a container for:
 * 1.  **Properties & Actions** (The base interface to the hardware).
 * 2.  **Capabilities** (Composable units of logic like FSM, Automation, Streaming).
 * 3.  **State** (Reactive properties).
 *
 * It serves as a [CoroutineScope] for all its internal operations and acts as a [Provider]
 * for introspection.
 */
public interface Device : PropertyDevice, ActionDevice, ContextAware, CoroutineScope, Provider {

    /**
     * The local name of this device instance within its parent hub.
     */
    public val name: Name

    /**
     * The configuration meta for the device. This is an [ObservableMeta], allowing the device
     * to react to configuration changes in real-time.
     */
    public val meta: ObservableMeta

    /**
     * A hot flow of messages originating from this device (property changes, events, errors).
     */
    public val messageFlow: SharedFlow<DeviceMessage>

    /**
     * The clock associated with this device. It may be a virtual clock for simulations.
     * Must be used for all timestamping.
     */
    public val clock: Clock

    /**
     * A shortcut accessor for the device's current lifecycle state.
     * This value is read directly from the standard [CoreDeviceSpec.LifecycleState] property.
     */
    public val lifecycleState: DeviceLifecycleState

    /**
     * Retrieves a specific [DeviceCapability] attached to this device.
     * This is the primary mechanism for accessing extended functionality.
     *
     * @param key The type-safe key identifying the requested capability.
     * @return The capability instance if present, or `null`.
     */
    public fun <C : DeviceCapability> capability(key: CapabilityKey<C>): C?

    // --- Provider Implementation ---

    /**
     * Provides content for DataForge's [Provider] mechanism.
     * Default targets include properties (`property`) and actions (`action`).
     */
    override fun content(target: String): Map<Name, Any> = emptyMap()

    // --- Data Plane Accessors ---

    /**
     * Opens a direct, typed accessor for a property (Data Plane).
     * If the driver does not support a specialized accessor, a generic fallback is returned.
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