package space.kscience.krig.core.operations

import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.gather

/**
 * Validation finding reported by a [BlueprintValidationHook]. ERROR blocks materialization;
 * WARNING is advisory.
 */
public data class BlueprintValidationMessage(
    val severity: Severity,
    val message: String,
    val category: String? = null,
) {
    public enum class Severity { ERROR, WARNING }
}

/**
 * Pre-materialization blueprint check. Discovered via DataForge `content()` on [TARGET];
 * canonical implementation lives in a dedicated validation FeatureSpec module.
 */
public fun interface BlueprintValidationHook {
    public fun validate(blueprint: DeviceBlueprint<*>): List<BlueprintValidationMessage>

    public companion object {
        public const val TARGET: String = "krig.blueprint.validation.hook"
    }
}

/** Runs every registered hook against [blueprint]; returns empty when none are installed. */
public fun Context.validateBlueprint(blueprint: DeviceBlueprint<*>): List<BlueprintValidationMessage> {
    val hooks = gather<BlueprintValidationHook>(BlueprintValidationHook.TARGET)
    if (hooks.isEmpty()) return emptyList()
    return hooks.values.flatMap { it.validate(blueprint) }
}

/** Thrown when [validateBlueprint] returns ERROR-severity findings. */
public class BlueprintValidationFailedException(
    public val blueprint: DeviceBlueprint<*>,
    public val messages: List<BlueprintValidationMessage>,
) : IllegalArgumentException(
    buildString {
        val errors = messages.count { it.severity == BlueprintValidationMessage.Severity.ERROR }
        val warnings = messages.count { it.severity == BlueprintValidationMessage.Severity.WARNING }
        append("Blueprint '${blueprint.id}' failed validation with $errors error(s) and $warnings warning(s):\n")
        for (m in messages) {
            append("  - [")
            append(m.severity)
            append("] ")
            append(m.message)
            append('\n')
        }
    },
)
