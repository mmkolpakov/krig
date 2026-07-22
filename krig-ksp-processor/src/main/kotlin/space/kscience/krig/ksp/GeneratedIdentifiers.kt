package space.kscience.krig.ksp

import java.security.MessageDigest

/** Bounded ASCII stem for an internal generated declaration; uniqueness comes from its digest suffix. */
internal fun String.generatedIdentifierStem(maxLength: Int): String = buildString(maxLength) {
    for (character in this@generatedIdentifierStem) {
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9') append(character)
        else append('_')
        if (length == maxLength) break
    }
}.trim('_').ifBlank { "Type" }

/** 96-bit deterministic discriminator used only in generated Kotlin identifiers and filenames. */
internal fun stableGeneratedToken(identity: String): String = MessageDigest.getInstance("SHA-256")
    .digest(identity.toByteArray(Charsets.UTF_8))
    .take(GENERATED_TOKEN_BYTES)
    .joinToString("") { byte -> "%02x".format(byte) }

private const val GENERATED_TOKEN_BYTES: Int = 12
