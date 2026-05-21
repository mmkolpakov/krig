package space.kscience.krig.api.messages

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.core.operations.HlcTimestamp

/** Operation context that belongs to transport/storage envelopes, not payload DTOs. */
@Serializable
public data class MessageContext(
    public val requestId: String? = null,
    public val correlationId: String? = null,
    public val hlcTimestamp: HlcTimestamp? = null,
    public val attributes: Meta = Meta.EMPTY,
) {
    public companion object {
        public val Empty: MessageContext = MessageContext()
    }
}

/** Payload plus optional tracing/causal context. */
@Serializable
public data class MessageEnvelope<out T : DeviceMessage>(
    public val payload: T,
    public val context: MessageContext = MessageContext.Empty,
)

public fun <T : DeviceMessage> T.envelope(context: MessageContext = MessageContext.Empty): MessageEnvelope<T> =
    MessageEnvelope(this, context)

public fun <T : DeviceMessage> MessageEnvelope<T>.withHlcStamp(stamp: HlcTimestamp): MessageEnvelope<T> =
    copy(context = context.copy(hlcTimestamp = stamp))

/** Drops envelope context when callers need the legacy payload-only view. */
public fun <T : DeviceMessage> Flow<MessageEnvelope<T>>.payloads(): Flow<T> =
    map { it.payload }

/** Typed correlation view; `null` when no correlation is attached. */
public val MessageContext.typedCorrelationId: CorrelationId?
    get() = CorrelationId.fromWire(correlationId)
