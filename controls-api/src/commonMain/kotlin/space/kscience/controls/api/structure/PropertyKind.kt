package space.kscience.controls.api.structure

import kotlinx.serialization.Serializable

/**
 * A semantic classification for a device property, describing its nature and origin.
 */
@Serializable
public enum class PropertyKind {
    /**
     * Represents a property whose value is read directly from a physical device.
     */
    PHYSICAL,

    /**
     * Represents a property that holds internal, managed state within a device.
     */
    LOGICAL,

    /**
     * Represents a property whose value is computed from one or more other properties.
     */
    DERIVED,

    /**
     * Represents a special kind of `DERIVED` property that always returns a `Boolean`.
     */
    PREDICATE,
}