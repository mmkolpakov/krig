package space.kscience.controls.api.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.attributes.Attributes
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import kotlin.random.Random

/**
 * A context for a single execution flow (a command plan, a query, or an action execution),
 * carrying cross-cutting concerns like security principals, tracing information, and local runtime attributes.
 *
 * This class is designed to be partially serializable. The [properties] field contains metadata
 * intended for network transmission (e.g., Trace ID, User ID), while the [attributes] field
 * contains transient, local-only objects (e.g., Coroutine Jobs, active connection handles) that
 * should not leave the current process.
 *
 * @property principal The identity of the caller. Defaults to a system principal.
 * @property correlationId A type-safe ID to trace a request through different components. Defaults to a random value.
 * @property originAddress The network address from which the original request was initiated. Can be null for internal requests.
 * @property properties A [Meta] object containing serializable metadata for the execution context
 *                      (e.g., W3C Trace Context headers). The runtime is responsible for propagating
 *                      this context across network boundaries.
 * @property attributes A container for transient, local-only attributes associated with this execution.
 *                      This field is **not serialized** and is used for passing runtime-specific objects
 *                      (like cancellation tokens or resource handles) within the local process.
 */
@Serializable
public data class ExecutionContext(
    val principal: Principal = SystemPrincipal,
    val correlationId: CorrelationId = CorrelationId("exec-${Random.nextLong().toString(16)}"),
    val originAddress: Address? = null,
    val properties: Meta = Meta.EMPTY,
    @Transient
    val attributes: Attributes = Attributes.EMPTY,
)