package space.kscience.controls.api.meta

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A base polymorphic interface for tags that provide semantic, domain-specific metadata
 * for device properties and actions.
 */
@Polymorphic
@Serializable
public sealed interface MemberTag

/**
 * A standard [MemberTag] for associating a device member with an alternative
 * name or identifier within a specific external system or protocol.
 */
@Serializable
@SerialName("tag.alias")
public data class AliasTag(
    val namespace: String,
    val alias: String,
) : MemberTag

/**
 * A standard [MemberTag] for formally declaring that a blueprint
 * implements a specific, named profile or conforms to a "dialect".
 */
@Serializable
@SerialName("tag.profile")
public data class ProfileTag(val name: String, val version: String) : MemberTag

/**
 * A test tag for verifying the extensibility of the MemberTag system.
 *
 * @property group A semantic group for UI elements.
 * @property widget A hint for a preferred UI widget.
 */
@Serializable
@SerialName("tag.ui.test")
public data class UiTestHint(val group: String, val widget: String? = null) : MemberTag