package space.kscience.krig.build

import java.security.MessageDigest

internal data class GeneratedNamespace(
    val value: String,
    val coordinateIdentity: String,
)

/** Stable Kotlin-safe namespace derived from the module's publication coordinates. */
internal fun generatedNamespace(
    group: String,
    projectName: String,
    defaultGroup: String,
): GeneratedNamespace {
    require(group.isNotBlank() && group != "unspecified" && group != defaultGroup) {
        "krig-mpp-ksp requires an explicit stable project group before KSP executes; " +
            "Gradle's path-derived default group is not a publication identity."
    }
    val coordinateIdentity = listOf(group, projectName).joinToString(separator = "\u0000")
    val readableArtifact = projectName.asPackageSegment().take(MAX_READABLE_STEM_LENGTH)
    val token = MessageDigest.getInstance("SHA-256")
        .digest(coordinateIdentity.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return GeneratedNamespace(
        value = "$readableArtifact.h$token",
        coordinateIdentity = coordinateIdentity,
    )
}

private fun String.asPackageSegment(): String {
    val slug = buildString(length) {
        var previousWasSeparator = false
        for (character in this@asPackageSegment) {
            val rendered = when (character) {
                in 'a'..'z', in '0'..'9' -> character
                in 'A'..'Z' -> (character.code + ('a'.code - 'A'.code)).toChar()
                else -> '_'
            }
            if (rendered == '_') {
                if (!previousWasSeparator && isNotEmpty()) append(rendered)
                previousWasSeparator = true
            } else {
                append(rendered)
                previousWasSeparator = false
            }
        }
    }.trim('_').ifBlank { "module" }
    return when {
        slug.first() in '0'..'9' -> "m_$slug"
        slug in KOTLIN_KEYWORDS -> "m_$slug"
        else -> slug
    }
}

private val KOTLIN_KEYWORDS: Set<String> = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

private const val MAX_READABLE_STEM_LENGTH: Int = 32
