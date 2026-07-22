package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment

internal data class GeneratedNamespace(
    val moduleSuffix: String,
    val packageName: String = "space.kscience.krig.generated.$moduleSuffix",
)

internal fun SymbolProcessorEnvironment.requireGeneratedNamespace(): GeneratedNamespace {
    val moduleSuffix = options["krig.generated.module"]
        ?: error(
            "krig-ksp-processor requires the 'krig.generated.module' KSP argument. " +
                "Apply the `krig-mpp-ksp` convention plugin which sets it automatically.",
        )
    return generatedNamespace(moduleSuffix)
}

internal fun generatedNamespace(moduleSuffix: String): GeneratedNamespace {
    require(moduleSuffix.length <= 512 && moduleSuffix.split('.').all(String::isKotlinPackageSegment)) {
        "Invalid krig.generated.module '$moduleSuffix': expected dot-separated Kotlin-safe identifiers."
    }
    return GeneratedNamespace(moduleSuffix)
}

private fun String.isKotlinPackageSegment(): Boolean =
    matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && this !in KOTLIN_KEYWORDS

private val KOTLIN_KEYWORDS: Set<String> = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)
