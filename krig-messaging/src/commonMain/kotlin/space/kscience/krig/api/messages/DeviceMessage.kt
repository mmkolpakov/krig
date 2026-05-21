package space.kscience.krig.api.messages

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * A polymorphic interface for all messages that flow through the device system.
 * JSON wire format uses the global `type` discriminator set by `krigJson`.
 *
 * Device endpoints are domain [Name]s. Transport routes, sessions, and physical
 * addresses belong to the surrounding envelope/context rather than this core DTO.
 */
@Polymorphic
@PolymorphicBase
public interface DeviceMessage {
    /** Stable domain type used by storage and routing. Must match the DTO `@SerialName`. */
    public val messageType: String

    public val sourceDevice: Name?
    public val targetDevice: Name?
    public val time: Instant

    /**
     * Creates a copy of this message with the source device name transformed by [block].
     * Used by composite devices to correctly namespace messages from their children.
     */
    public fun changeSource(block: (Name) -> Name): DeviceMessage
}

/** A message that initiates a request and expects a response. */
public interface RequestMessage : DeviceMessage

/** A message that is a response to a [RequestMessage]. */
public interface ResponseMessage : DeviceMessage
