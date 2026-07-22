package space.kscience.krig.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier

/** Every non-local source class currently up for processing, including nested classes. */
internal fun Iterable<KSFile>.getAllClassDeclarations(): Sequence<KSClassDeclaration> =
    asSequence().flatMap { sourceFile ->
        sourceFile.declarations
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { declaration -> declaration.withNestedClasses() }
    }

private fun KSClassDeclaration.withNestedClasses(): Sequence<KSClassDeclaration> = sequence {
    yield(this@withNestedClasses)
    for (nestedDeclaration in declarations.filterIsInstance<KSClassDeclaration>()) {
        yieldAll(nestedDeclaration.withNestedClasses())
    }
}

internal fun MutableList<KSAnnotated>.addOnce(symbol: KSAnnotated) {
    if (none { candidate -> candidate === symbol || candidate == symbol }) add(symbol)
}

/** Resolves a possibly chained Kotlin typealias to its class declaration. */
internal fun KSDeclaration.actualClassDeclaration(): KSClassDeclaration? {
    var current: KSDeclaration = this
    val visitedAliases = mutableSetOf<String>()
    while (current is KSTypeAlias) {
        val aliasFqn = current.qualifiedName?.asString() ?: return null
        if (!visitedAliases.add(aliasFqn)) return null
        current = current.type.resolve().declaration
    }
    return current as? KSClassDeclaration
}

/** Whether a declaration can be referenced from a generated top-level file in the same module. */
internal fun KSDeclaration.isAccessibleFromGeneratedCode(): Boolean {
    var current: KSDeclaration? = this
    while (current != null) {
        if (Modifier.PRIVATE in current.modifiers || Modifier.PROTECTED in current.modifiers) return false
        current = current.parentDeclaration
    }
    return true
}
