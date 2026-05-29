package space.kscience.krig.core.operations

import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.gather

/**
 * Validation finding reported by a [ManifestValidationHook]. ERROR blocks materialization;
 * WARNING is advisory.
 */
public data class ManifestValidationMessage(
    val severity: Severity,
    val message: String,
    val category: String? = null,
) {
    public enum class Severity { ERROR, WARNING }
}

/**
 * Pre-materialization Manifest check. Discovered via DataForge `content()` on [TARGET].
 */
public fun interface ManifestValidationHook {
    public fun validate(manifest: DeviceManifest): List<ManifestValidationMessage>

    public companion object {
        public const val TARGET: String = "krig.manifest.validation.hook"
    }
}

/** Runs every registered hook against [manifest]; returns empty when none are installed. */
public fun Context.validateManifest(manifest: DeviceManifest): List<ManifestValidationMessage> {
    val hooks = gather<ManifestValidationHook>(ManifestValidationHook.TARGET)
    if (hooks.isEmpty()) return emptyList()
    return hooks.values.flatMap { it.validate(manifest) }
}

/** Thrown when [validateManifest] returns ERROR-severity findings. */
public class ManifestValidationFailedException(
    public val manifest: DeviceManifest,
    public val messages: List<ManifestValidationMessage>,
) : IllegalArgumentException(
    buildString {
        val errors = messages.count { it.severity == ManifestValidationMessage.Severity.ERROR }
        val warnings = messages.count { it.severity == ManifestValidationMessage.Severity.WARNING }
        append("Manifest '${manifest.id}' failed validation with $errors error(s) and $warnings warning(s):\n")
        for (m in messages) {
            append("  - [")
            append(m.severity)
            append("] ")
            append(m.message)
            append('\n')
        }
    },
)
