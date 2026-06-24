package space.kscience.krig.api.messages

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.api.data.HlcTimestamp

/** Operation context that belongs to transport/storage frames, not payload DTOs. */
@Serializable
public data class MessageContext(
    public val correlationId: CorrelationId? = null,
    public val hlcTimestamp: HlcTimestamp? = null,
    /**
     * Cryptographically attested identity of the sending node (e.g. a SPIFFE ID from an mTLS peer
     * certificate), stamped by an authenticating transport — distinct from any self-asserted
     * `callerIdentity` string in a request payload. `null` for in-process or unauthenticated frames;
     * an authorization service resolves a non-null value to a `DevicePrincipal`. The verifying
     * transport is the only writer; the data model lives here so M2M identity travels with the frame.
     */
    public val verifiedIdentity: String? = null,
    public val attributes: Meta = Meta.EMPTY,
) {
    public companion object {
        public val Empty: MessageContext = MessageContext()
    }
}

/**
 * KRig's typed message frame: a [DeviceMessage] payload plus optional tracing/causal
 * [context]. Distinct from DataForge's `Envelope` (schemaless meta + binary block):
 * a frame is the in-process/journal carrier and can be *lowered* into a DataForge
 * `Envelope` via the frame codec when file/stream storage is needed.
 */
@Serializable
public data class DeviceMessageFrame<out T : DeviceMessage>(
    public val payload: T,
    public val context: MessageContext = MessageContext.Empty,
)

public fun <T : DeviceMessage> T.frame(context: MessageContext = MessageContext.Empty): DeviceMessageFrame<T> =
    DeviceMessageFrame(this, context)

public fun <T : DeviceMessage> DeviceMessageFrame<T>.withHlcStamp(stamp: HlcTimestamp): DeviceMessageFrame<T> =
    copy(context = context.copy(hlcTimestamp = stamp))

/** Drops frame context when callers need the payload-only view. */
public fun <T : DeviceMessage> Flow<DeviceMessageFrame<T>>.payloads(): Flow<T> =
    map { it.payload }
