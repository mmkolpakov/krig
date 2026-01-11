package space.kscience.controls.composite.protocol.api

import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.composite.ports.Port
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a factory that creates [ProtocolChannel]s.
 * The adapter encapsulates the logic of a specific communication protocol (e.g., Modbus, OPC-UA).
 *
 * This abstraction separates:
 * - **Device Logic** (Drivers)
 * - **Protocol Logic** (Adapters/Channels)
 * - **Transport Logic** (Ports)
 */
public interface ProtocolAdapter {

    /**
     * Creates a new [ProtocolChannel] over the given [Port].
     * This method is responsible for any protocol-level initialization, such as handshakes
     * or resetting transaction counters.
     *
     * @param port The low-level transport port (TCP, Serial, etc.).
     * @param context The context for logging and resource management.
     * @return An active [ProtocolChannel] ready for communication.
     */
    public fun createChannel(port: Port, context: Context): ProtocolChannel
}