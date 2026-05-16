package space.kscience.krig.api.meta

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.annotations.PolymorphicBase

/**
 * Polymorphic semantic tag for device properties and actions.
 *
 * The core SDK only ships generic tags. Domain modules register their own
 * `@Serializable` implementations through the KSP-generated serializers module.
 */
@Polymorphic
@PolymorphicBase
public interface MemberTag

/** Formal declaration that a blueprint conforms to a named profile or dialect. */
@Serializable
@SerialName("tag.profile")
public data class ProfileTag(val name: String, val version: String) : MemberTag
