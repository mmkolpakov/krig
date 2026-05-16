package space.kscience.krig.api.context

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.Meta

/**
 * The identity of the caller performing an action or query.
 * Open [Polymorphic] hierarchy — third parties register custom subclasses via
 * `SerializationContributor`.
 */
@Polymorphic
@PolymorphicBase
public interface Principal {
    public val name: String
    public val roles: Set<String>
    public val attributes: Meta
}

/** A simple, data-holding implementation of [Principal]. */
@Serializable
@SerialName("principal.simple")
public data class SimplePrincipal(
    override val name: String,
    override val roles: Set<String> = emptySet(),
    override val attributes: Meta = Meta.EMPTY,
) : Principal

/** Neutral fallback identity for unauthenticated calls. Carries no roles or privileges. */
@Serializable
@SerialName("principal.anonymous")
public data object AnonymousPrincipal : Principal {
    override val name: String = "anonymous"
    override val roles: Set<String> = emptySet()
    override val attributes: Meta = Meta.EMPTY
}
