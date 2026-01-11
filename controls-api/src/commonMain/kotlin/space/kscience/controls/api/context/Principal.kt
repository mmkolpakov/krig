package space.kscience.controls.api.context

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta

/**
 * Represents the identity of the caller performing an action or query.
 * This can be extended to include authentication tokens, roles, and other security attributes.
 *
 * @property name A human-readable name for the principal (e.g., username).
 * @property roles A set of roles associated with the principal, used for authorization.
 * @property attributes A [Meta] object containing additional, arbitrary attributes about the principal
 *                      (e.g., session ID, source IP address, authentication token details).
 */
@Polymorphic
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
@Serializable
public object SystemPrincipal : Principal {
    override val name: String = "system"
    override val roles: Set<String> = setOf("system")
    override val attributes: Meta = Meta.EMPTY
}