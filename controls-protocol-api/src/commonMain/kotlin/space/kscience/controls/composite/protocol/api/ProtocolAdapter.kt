package space.kscience.controls.composite.protocol.api

import space.kscience.controls.api.structure.ActionDescriptor
import space.kscience.controls.api.structure.PropertyDescriptor
import space.kscience.controls.composite.ports.Port
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a component that encapsulates the logic of a specific communication protocol.
 * The adapter is responsible for translating high-level property/action requests into raw byte
 * sequences for a given protocol and parsing the responses.
 *
 * This interface provides a critical layer of abstraction, separating:
 * - **Device Logic** (what a device does, its properties and actions) - defined in `DeviceBlueprint` and `DeviceDriver`.
 * - **Protocol Logic** (how to format/parse messages for a protocol like Modbus) - implemented by a `ProtocolAdapter`.
 * - **Transport Logic** (how to send/receive bytes over TCP, Serial, etc.) - handled by a [Port].
 *
 * An implementation of this interface should be stateless and reusable. All necessary configuration
 * for a specific operation (like a Modbus register address) should be read from the provided
 * [PropertyDescriptor] or [ActionDescriptor].
 */
public interface ProtocolAdapter {
    /**
     * Reads a property from a device using the given [Port].
     *
     * @param port The low-level communication port to use.
     * @param property The descriptor of the property to read. The adapter should use metadata
     *                 within this descriptor to construct the protocol-specific request.
     * @return A [Meta] object representing the value read from the device.
     * @throws space.kscience.controls.composite.ports.PortException on I/O or communication errors.
     * @throws IllegalStateException if the property descriptor lacks the necessary protocol-specific configuration.
     */
    public suspend fun readProperty(port: Port, property: PropertyDescriptor): Meta

    /**
     * Writes a value to a property on a device using the given [Port].
     *
     * @param port The low-level communication port to use.
     * @param property The descriptor of the property to write to.
     * @param value The [Meta] value to write.
     * @throws space.kscience.controls.composite.ports.PortException on I/O or communication errors.
     * @throws IllegalStateException if the property is read-only or lacks configuration.
     */
    public suspend fun writeProperty(port: Port, property: PropertyDescriptor, value: Meta)

    /**
     * Executes an action on a device using the given [Port].
     *
     * @param port The low-level communication port to use.
     * @param action The descriptor of the action to execute.
     * @param argument An optional [Meta] argument for the action.
     * @return An optional [Meta] object representing the result of the action.
     * @throws space.kscience.controls.composite.ports.PortException on I/O or communication errors.
     */
    public suspend fun execute(port: Port, action: ActionDescriptor, argument: Meta?): Meta?
}