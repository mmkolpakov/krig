package space.kscience.controls.api.messages

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.faults.DeviceFault
import space.kscience.controls.api.faults.SerializableDeviceFailure
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * A sealed interface for all messages that flow through the device system.
 *
 * @property sourceDevice The network-wide address of the device that originated the message. Null for system-generated messages.
 * @property targetDevice The intended recipient of the message. Null for broadcast messages.
 * @property time The timestamp when the message was created.
 * @property requestId A unique identifier for a request, allowing responses to be correlated. Null for notifications.
 * @property correlationId A unique identifier to trace a single logical operation across multiple messages and devices.
 */
@Polymorphic
public interface DeviceMessage {
    public val sourceDevice: Address?
    public val targetDevice: Address?
    public val time: Instant
    public val requestId: String?
    public val correlationId: CorrelationId?

    /**
     * Creates a copy of a message, modifying the source device's local name by applying a prefix.
     * The hub ID remains unchanged. This is used by composite devices to correctly namespace messages from their children.
     */
    public fun changeSource(block: (Name) -> Name): DeviceMessage
}

/**
 * A message that initiates a request and expects a response.
 */
public interface RequestMessage : DeviceMessage {
    override val requestId: String
}

/**
 * A message that is a response to a [RequestMessage].
 */
public interface ResponseMessage : DeviceMessage {
    override val requestId: String
}
