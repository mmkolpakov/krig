package space.kscience.controls.validation

import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.dataforge.names.Name

/**
 * A sealed interface representing a specific validation error found in a [DeviceBlueprint].
 */
public sealed interface ValidationError {
    public val message: String

    /** A validation error indicating a name collision between different types of members. */
    public data class ConflictingName(val name: Name) : ValidationError {
        override val message: String get() = "Name collision for '$name'. Properties, actions, streams, children, and peer connections must have unique names."
    }

    /** A validation error indicating an issue with a property binding for a child device. */
    public data class InvalidBinding(val childName: Name, val propertyName: Name, val reason: String) :
        ValidationError {
        override val message: String get() = "Invalid binding for property '$propertyName' of child '$childName': $reason"
    }

    /** A validation error indicating that a required component, like a driver, is missing. */
    public data class MissingComponent(val componentName: String, val context: String) : ValidationError {
        override val message: String get() = "Missing required component '$componentName' in blueprint '$context'."
    }

    /** A validation warning for features that are declared but not used. */
    public data class SuperfluousCapability(val blueprintId: String, val capabilityName: String) : ValidationError {
        override val message: String get() = "Blueprint '$blueprintId' declares capability '$capabilityName' via its feature, but does not appear to use any members that require it. This might be unintentional."
    }

    /** A validation error indicating that a blueprint uses a capability but does not declare the corresponding feature. */
    public data class InconsistentCapability(val blueprintId: String, val capabilityName: String) : ValidationError {
        override val message: String get() = "Blueprint '$blueprintId' uses members requiring '$capabilityName' but does not declare the corresponding feature."
    }

    /** A validation error indicating that a child's blueprint could not be found in the registry. */
    public data class BlueprintNotFound(val childName: Name, val blueprintId: BlueprintId) : ValidationError {
        override val message: String get() = "Blueprint with ID '${blueprintId}' for child '$childName' not found in the registry."
    }

    /** A validation error related to a remote child configuration. */
    public data class InvalidRemoteChild(val childName: Name, val reason: String) : ValidationError {
        override val message: String get() = "Invalid configuration for remote child '$childName': $reason"
    }

    /** A validation error related to an operational guard. */
    public data class InvalidGuard(val blueprintId: String, val predicateName: Name, val reason: String) : ValidationError {
        override val message: String get() = "Invalid guard in blueprint '$blueprintId' for predicate '$predicateName': $reason"
    }

    /** A validation error related to an action's precondition. */
    public data class InvalidActionRequirement(
        val blueprintId: String,
        val actionName: Name,
        val predicateName: Name,
        val reason: String,
    ) : ValidationError {
        override val message: String get() = "Invalid requirement for action '$actionName' in blueprint '$blueprintId': $reason"
    }

    /** A validation error related to a remote property mirror. */
    public data class InvalidMirror(val blueprintId: String, val localName: Name, val reason: String) : ValidationError {
        override val message: String get() = "Invalid mirror '$localName' in blueprint '$blueprintId': $reason"
    }

    /** A validation error for a cyclical dependency between blueprints. */
    public data class CyclicalDependency(val path: List<BlueprintId>) : ValidationError {
        override val message: String get() = "Cyclical blueprint dependency detected: ${path.joinToString(" -> ") { it.value }}"
    }

    /** A generic validation error for other issues. */
    public data class GenericError(val context: String, override val message: String) : ValidationError
}