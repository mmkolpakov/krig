package space.kscience.krig.api.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.attributes.Attributes
import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta

/**
 * A context for a single execution flow (a command plan, a query, or an action execution),
 * carrying cross-cutting concerns like security principals, tracing information, and local runtime attributes.
 *
 * [properties] contains serializable metadata intended for network transmission (e.g., Trace ID),
 * while [attributes] holds transient local-only objects (e.g., coroutine jobs, connection handles)
 * that must not leave the current process.
 */
@Serializable
public data class ExecutionContext(
    val principal: Principal = AnonymousPrincipal,
    val correlationId: CorrelationId = CorrelationId.Unspecified,
    val originAddress: Address? = null,
    val properties: Meta = Meta.EMPTY,
    @Transient
    val attributes: Attributes = Attributes.EMPTY,
)
