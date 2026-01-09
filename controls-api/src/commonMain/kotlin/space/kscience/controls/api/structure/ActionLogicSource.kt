package space.kscience.controls.api.structure

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * A sealed interface representing the source of an action's executable logic.
 * This provides a strict, type-safe, and declarative way to specify how an action
 * should be executed.
 */
@Polymorphic
public interface ActionLogicSource

/**
 * Specifies that the action's logic is implemented by an external, reusable, and versioned component.
 *
 * @property logicId The unique, hierarchical name of the external logic component.
 * @property versionConstraint An optional version string or range.
 */
@Serializable
@SerialName("logic.external")
public data class ExternalLogic(
    val logicId: Name,
    val versionConstraint: String? = null
) : ActionLogicSource
