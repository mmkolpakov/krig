package space.kscience.controls.core.contracts

import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.dataforge.meta.Meta

/**
 * Represents a raw connection to physical hardware or a protocol adapter.
 *
 * This corresponds to the **Southbound Adapter** or **HAL** (Hardware Abstraction Layer).
 *
 * **Responsibilities:**
 * - Protocol Encoding/Decoding: converting logical operations (read "Voltage") into transport frames (Modbus TCP 0x03...).
 * - Transport Management: holding sockets, serial ports, or client handles.
 * - Stateless Execution: The connection does not know about the device's operational state or lifecycle.
 *   It simply executes commands.
 *
 * **Lifecycle:**
 * The connection is typically opened when the [Device] starts and closed when it stops.
 */
public interface DeviceConnection : AutoCloseable {

    /**
     * Reads a property value directly from the hardware or adapter.
     *
     * The implementation uses metadata from the provided [property] descriptor (e.g., register address,
     * OID, channel number) to formulate the specific request.
     *
     * @param property The descriptor of the property to read.
     * @return The raw value returned by the device, wrapped in [Meta].
     * @throws space.kscience.controls.composite.ports.PortException if the communication fails.
     */
    public suspend fun read(property: PropertyDescriptor): Meta

    /**
     * Writes a value to a property on the hardware or adapter.
     *
     * @param property The descriptor of the property to write.
     * @param value The value to write.
     * @throws space.kscience.controls.composite.ports.PortException if the communication fails.
     */
    public suspend fun write(property: PropertyDescriptor, value: Meta)

    /**
     * Executes an action on the hardware or adapter.
     *
     * @param action The descriptor of the action to execute.
     * @param argument The optional input argument for the action.
     * @return The result of the action wrapped in [Meta], or null if the action returns `Unit`.
     * @throws space.kscience.controls.composite.ports.PortException if the communication fails.
     */
    public suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta?

    /**
     * Closes the connection and releases all associated resources (sockets, file handles, threads).
     * This operation is idempotent.
     */
    override fun close()
}