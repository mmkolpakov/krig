package space.kscience.controls.api.context

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.dataforge.meta.Meta

/**
 * Represents the identity of an actor (User, Service, or Device) performing an action.
 * Used for Role-Based Access Control (RBAC) and Audit Logging.
 *
 * ### Data Separation Principle
 * - **[meta] (DTO)**: Contains static, serializable identity data (e.g., `email`, `tenantId`, `displayName`).
 *   These are always transmitted over the network.
 * - **[attributes] (Runtime)**: Contains ephemeral or typed objects (e.g., `UserSession`, `LDAPEntry`, `SecurityToken`).
 *   Serialization of these attributes depends on the runtime configuration (see [ExecutionContext]).
 */
public interface Principal {
    /**
     * The unique, stable name or ID of the principal.
     */
    public val name: String

    /**
     * A set of abstract role identifiers assigned to this principal.
     */
    public val roles: Set<String>

    /**
     * Serializable metadata associated with the principal.
     */
    public val meta: Meta

    /**
     * Type-safe runtime attributes.
     */
    public val attributes: Attributes
}

/**
 * A standard, serializable implementation of [Principal].
 */
@Serializable
public data class SimplePrincipal(
    override val name: String,
    override val roles: Set<String> = emptySet(),
    override val meta: Meta = Meta.EMPTY,
    @Contextual
    override val attributes: Attributes = Attributes.EMPTY,
) : Principal

/**
 * A singleton principal representing the internal system actor (Superuser).
 * Typically used for internal maintenance tasks or startup sequences.
 */
public object SystemPrincipal : Principal {
    override val name: String = "system"
    override val roles: Set<String> = setOf("system", "admin")
    override val meta: Meta = Meta.EMPTY
    override val attributes: Attributes = Attributes.EMPTY
}