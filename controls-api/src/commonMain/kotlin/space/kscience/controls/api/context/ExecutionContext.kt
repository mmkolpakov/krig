package space.kscience.controls.api.context

import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import kotlin.random.Random

/**
 * A context for a single execution flow (a command plan or a query), carrying cross-cutting concerns
 * like security principal and tracing information.
 *
 * @param principal The identity of the caller. Defaults to a system principal.
 * @param correlationId A type-safe ID to trace a request through different components. Defaults to a random value.
 * @param originAddress The network address from which the original request was initiated. Can be null for internal requests.
 * @param fromCache Indicates that the primary result of this execution context was retrieved from a cache rather than being computed live.
 * @param traceContext An optional map containing trace propagation headers (e.g., W3C Trace Context).
 *                     This allows for seamless integration with distributed tracing systems like OpenTelemetry.
 *                     The runtime is responsible for propagating this context across network boundaries.
 * @param attributes Additional metadata for the execution context, for extensibility.
 */
@Serializable
public data class ExecutionContext(
    val principal: Principal = SystemPrincipal,
    val correlationId: CorrelationId = CorrelationId("exec-${Random.nextLong().toString(16)}"),
    val originAddress: Address? = null,
    val fromCache: Boolean = false,
    val traceContext: Map<String, String>? = null,
    val attributes: Meta = Meta.EMPTY,
)