package space.kscience.krig.api.messages

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.core.operations.HlcTimestamp
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * A polymorphic interface for all messages that flow through the device system.
 * JSON wire format uses the global `type` discriminator set by `krigJson`.
 *
 * [correlationId] is stored as `String` to avoid per-message boxing of a nullable
 * value class; the typed view is available through [typedCorrelationId].
 *
 * [hlcTimestamp] carries a Hybrid Logical Clock stamp for distributed causal ordering;
 * `null` when the emitter has no HLC configured. See
 * [HybridLogicalClock][space.kscience.krig.core.operations.HybridLogicalClock].
 *
 * Device endpoints are domain [Name]s. Transport routes, sessions, and physical
 * addresses belong to the surrounding envelope/context rather than this core DTO.
 *
 * @property requestId Non-null for request/response pairs; null for notifications.
 * @property correlationId Traces a single logical operation across multiple messages and devices.
 */
@Polymorphic
@PolymorphicBase
public interface DeviceMessage {
    /** Stable domain type used by storage and routing. Must match the DTO `@SerialName`. */
    public val messageType: String

    public val sourceDevice: Name?
    public val targetDevice: Name?
    public val time: Instant
    @get:Suppress("EmptyMethod", "SameReturnValue")
    public val requestId: String?
    public val correlationId: String?
    @get:Suppress("SameReturnValue")
    public val hlcTimestamp: HlcTimestamp? get() = null

    /**
     * Creates a copy of this message with the source device name transformed by [block].
     * Used by composite devices to correctly namespace messages from their children.
     */
    public fun changeSource(block: (Name) -> Name): DeviceMessage

    /** Returns a copy of this message with [hlcTimestamp] set to [stamp]. */
    public fun withHlcStamp(stamp: HlcTimestamp): DeviceMessage
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

/** Typed view of [DeviceMessage.correlationId]; `null` when no correlation is attached. */
public val DeviceMessage.typedCorrelationId: CorrelationId?
    get() = CorrelationId.fromWire(correlationId)
