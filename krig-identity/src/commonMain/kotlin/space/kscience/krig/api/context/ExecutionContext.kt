package space.kscience.krig.api.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.attributes.Attributes
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A context for a single execution flow (a command plan, a query, or an action execution),
 * carrying cross-cutting concerns like security principals, tracing information, and local runtime attributes.
 * [originDevice] is the optional domain device identity that initiated the flow; transport
 * route/session details should be stored in [properties] or local [attributes].
 * [callerIdentity] is the serialized ingress identity from request messages. It is resolved locally
 * by `IdentityProvider` when [principal] is still [AnonymousPrincipal].
 *
 * [properties] contains serializable metadata intended for network transmission (e.g., Trace ID),
 * while [attributes] holds transient local-only objects (e.g., coroutine jobs, connection handles)
 * that must not leave the current process.
 *
 * [onBehalfOf] preserves the originating principal across a delegated call: when service A invokes a
 * device on behalf of end user C, [principal] is A while [onBehalfOf] is C, so audit keeps the full
 * delegation chain instead of attributing the action to the intermediary alone.
 *
 * Note on equality: [attributes] is `@Transient` (not serialized) yet still participates in the
 * data-class `equals`/`hashCode`. Two contexts that are identical on the wire may therefore compare
 * unequal locally when their transient attributes differ — intentional, because attributes carry
 * process-local state that distinguishes live flows.
 */
@Serializable
public data class ExecutionContext(
    val principal: Principal = AnonymousPrincipal,
    val correlationId: CorrelationId = CorrelationId.Unspecified,
    val originDevice: Name? = null,
    val callerIdentity: String? = null,
    val onBehalfOf: Principal? = null,
    val properties: Meta = Meta.EMPTY,
    @Transient
    val attributes: Attributes = Attributes.EMPTY,
)
