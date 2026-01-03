package space.kscience.controls.core.contracts

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * The primary public API for interacting with a hierarchy of composite devices.
 *
 * This interface defines high-level, transactional operations for managing the entire lifecycle of devices.
 * It is designed to be the main entry point for user code and external systems (like a UI or a remote client).
 * All operations are suspendable and ensure atomicity where applicable.
 */
public interface DeviceHub {
    /**
     * An immutable map of all currently registered top-level devices, keyed by their local unique name.
     * To access child devices, one must first retrieve the parent device.
     */
    public val devices: Map<Name, Device>

    /**
     * Reads a property from a specific device within the hub.
     *
     * @param deviceName The name of the target device.
     * @param propertyName The name of the property to read.
     * @param context The [ExecutionContext] for this operation.
     * @return The property value as a [space.kscience.dataforge.meta.Meta] object.
     * @throws space.kscience.controls.core.faults.DeviceNotFoundInCompositeHubException if the device does not exist.
     * @throws space.kscience.controls.core.faults.DevicePropertyException if the read operation fails.
     */
    public suspend fun readProperty(
        deviceName: Name,
        propertyName: Name,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta

    /**
     * Writes a value to a property of a specific device within the hub.
     *
     * @param deviceName The name of the target device.
     * @param propertyName The name of the property to write.
     * @param value The [Meta] value to write.
     * @param context The [ExecutionContext] for this operation.
     * @throws space.kscience.controls.core.faults.DeviceNotFoundInCompositeHubException if the device does not exist.
     * @throws space.kscience.controls.core.faults.DevicePropertyException if the write operation fails.
     */
    public suspend fun writeProperty(
        deviceName: Name,
        propertyName: Name,
        value: Meta,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    )

    /**
     * Executes an action on a specific device within the hub.
     *
     * @param deviceName The name of the target device.
     * @param actionName The name of the action to execute.
     * @param argument An optional [Meta] argument for the action.
     * @param context The [ExecutionContext] for this operation.
     * @return An optional [Meta] result from the action, or `null` if the action does not return a value.
     * @throws space.kscience.controls.core.faults.DeviceNotFoundInCompositeHubException if the device does not exist.
     * @throws space.kscience.controls.core.faults.DeviceActionException if the action execution fails.
     */
    public suspend fun execute(
        deviceName: Name,
        actionName: Name,
        argument: Meta? = null,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?
}