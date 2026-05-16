package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A semantic classification for a device property, describing its nature and origin.
 *
 * Sealed hierarchy — SDK ships [PHYSICAL] and [LOGICAL]; integrations add domain-specific
 * kinds (DERIVED, COMPUTED, STREAM, …) via `@Serializable data object MyKind : PropertyKind`
 * registered through [SerializationContributor][space.kscience.krig.api.serialization.SerializationContributor].
 */
@Serializable
public sealed interface PropertyKind {
    /**
     * A property whose value is read directly from a physical device.
     */
    @Serializable
    @SerialName("kind.physical")
    public data object PHYSICAL : PropertyKind

    /**
     * A property that holds internal, managed state within a device.
     */
    @Serializable
    @SerialName("kind.logical")
    public data object LOGICAL : PropertyKind

    public companion object
}