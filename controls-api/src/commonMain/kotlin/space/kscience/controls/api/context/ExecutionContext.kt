package space.kscience.controls.api.context

import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import kotlin.random.Random

/**
 * Represents the identity of the caller performing an action or query.
 * This can be extended to include authentication tokens, roles, and other security attributes.
 *
 * @property name A human-readable name for the principal (e.g., username).
 * @property roles A set of roles associated with the principal, used for authorization.
 * @property attributes A [Meta] object containing additional, arbitrary attributes about the principal
 *                      (e.g., session ID, source IP address, authentication token details).
 */
public interface Principal {
    public val name: String
    public val roles: Set<String>
    public val attributes: Meta
}

/**
 * A simple, data-holding implementation of [Principal].
 */
@Serializable
public data class SimplePrincipal(
    override val name: String,
    override val roles: Set<String> = emptySet(),
    override val attributes: Meta = Meta.EMPTY,
) : Principal

/**
 * A system-level principal used for internal operations or when no specific principal is provided.
 * By default, it has all permissions.
 */
public object SystemPrincipal : Principal {
    override val name: String = "system"
    override val roles: Set<String> = setOf("system")
    override val attributes: Meta = Meta.EMPTY
}

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