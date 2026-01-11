package space.kscience.controls.composite.protocol.api

import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.composite.ports.Port
import space.kscience.dataforge.meta.Meta

/**
 * Represents an active communication channel (session) established over a [Port].
 * Unlike the stateless [ProtocolAdapter], a [ProtocolChannel] can maintain state,
 * such as transaction counters, authentication tokens, or handshake status.
 */
public interface ProtocolChannel : AutoCloseable {
    /**
     * Reads a property from the device using this channel.
     * The channel uses the [PropertyDescriptor] to format the protocol-specific request.
     */
    public suspend fun read(property: PropertyDescriptor): Meta

    /**
     * Writes a value to a property on the device using this channel.
     */
    public suspend fun write(property: PropertyDescriptor, value: Meta)

    /**
     * Executes an action on the device using this channel.
     */
    public suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta?
}