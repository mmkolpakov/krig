package space.kscience.controls.api.context

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.attributes.AttributesBuilder
import space.kscience.attributes.modified
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import kotlin.random.Random

/**
 * A context container for a single execution flow (e.g., a command, a query, or a transaction).
 * It carries metadata through the system layers, spanning both the Control Plane (In-Memory)
 * and Data Plane (Network/Persistence).
 *
 * ### Serialization Strategy
 * The [attributes] field is marked as [Contextual]. This means the specific set of attributes
 * that are transmitted over the wire is determined by the [kotlinx.serialization.modules.SerializersModule]
 * configured in the runtime.
 *
 * - **Runtime Attributes** (e.g., DB Transactions, Auth Tokens) are typically **Transient** and dropped during serialization.
 * - **Protocol Attributes** (e.g., TraceID, Priority) are registered in the serializer and preserved.
 *
 * @property principal The identity of the caller initiating this execution.
 * @property correlationId A unique identifier for distributed tracing and log correlation.
 * @property originAddress The network address of the caller, if applicable.
 * @property fromCache Indicates whether the result associated with this context was served from a cache.
 * @property attributes A type-safe container for extension data.
 */
@Serializable
public data class ExecutionContext(
    val principal: Principal = SystemPrincipal,
    val correlationId: CorrelationId = CorrelationId("exec-${Random.nextLong().toString(16)}"),
    val originAddress: Address? = null,
    val fromCache: Boolean = false,
    @Contextual
    val attributes: Attributes = Attributes.EMPTY,
) {
    /**
     * Creates a copy of this context with modified attributes using the provided [builder].
     * This is a zero-copy operation if no changes are actually made.
     */
    public fun withAttributes(builder: AttributesBuilder<*>.() -> Unit): ExecutionContext {
        return copy(attributes = attributes.modified<ExecutionContext>(builder))
    }
}