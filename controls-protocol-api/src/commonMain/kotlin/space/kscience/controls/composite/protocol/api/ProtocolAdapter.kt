package space.kscience.controls.composite.protocol.api

import space.kscience.controls.composite.ports.Port
import space.kscience.dataforge.context.Context

/**
 * A contract for a factory that creates [ProtocolChannel]s (which are [DeviceConnection]s).
 *
 * The adapter encapsulates the logic of a specific communication protocol (e.g., Modbus, OPC-UA, VISA).
 * It serves as a bridge between the raw transport ([Port]) and the device driver.
 *
 * **Architecture:**
 * - **Device Factory**: Instantiates the device.
 * - **Protocol Adapter**: Creates the connection logic.
 * - **Port**: Handles raw bytes I/O.
 */
public interface ProtocolAdapter {

    /**
     * Creates a new [ProtocolChannel] over the given [Port].
     *
     * This method is responsible for any protocol-level initialization, such as handshakes,
     * negotiating capabilities, or resetting transaction counters.
     *
     * @param port The low-level transport port (TCP, Serial, etc.).
     * @param context The context for logging and resource management.
     * @return An active [ProtocolChannel] ready for communication.
     */
    public fun createChannel(port: Port, context: Context): ProtocolChannel
}