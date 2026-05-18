package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * Hybrid KSP generator for the krig polymorphic serializers registry.
 *
 * - Per `@Serializable` subclass of an `@PolymorphicBase`-annotated interface emits one
 *   small `<SubclassName>Contributor.kt` file with `Dependencies(aggregating = false,
 *   subclass.containingFile)`. Editing or removing one class invalidates exactly that
 *   one generated file.
 * - One small index `GeneratedKrigSerializersModule.kt` per module aggregates the
 *   per-class contributors via `SerializersModule { include(...) }`. The index is
 *   `aggregating = true` so adding/removing a polymorphic class updates the list, but
 *   editing the class body does not — it only touches the per-class contributor.
 *
 * Downstream modules import the public `generatedKrigSerializersModule` from the
 * generator-emitted package and compose it with their own contributions.
 */
internal class SerializersModuleGenerator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    private companion object {
        const val POLYMORPHIC_BASE_FQN = "space.kscience.krig.api.annotations.PolymorphicBase"
        const val SERIALIZABLE_FQN = "kotlinx.serialization.Serializable"
    }

    /** FQNs of subclass contributors already emitted in this compilation. Multi-round dedup. */
    private val emittedContributors: MutableSet<String> = mutableSetOf()
    /** Primitive contributor metadata collected across rounds. Never stores KS symbols. */
    private val collectedContributors: MutableMap<String, ContributorRef> = linkedMapOf()
    /** Latch for the aggregating index, emitted only after a clean round with no new symbols. */
    private var emittedIndex: Boolean = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        // Bases via getSymbolsWithAnnotation. We only need the FQNs to recognise
        // subclasses; deferring is still meaningful for first round.
        val baseInterfaces = resolver.getSymbolsWithAnnotation(POLYMORPHIC_BASE_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                if (!it.validate()) {
                    deferred += it; false
                } else true
            }
            .associateBy { it.qualifiedName?.asString() ?: "" }

        // Collect every @Serializable subclass of each @PolymorphicBase interface.
        // The final index is an aggregating output and records all containing files in
        // Dependencies, so KSP can invalidate it when the source set changes.
        val allSubclasses = collectAllSubclasses(resolver, baseInterfaces, deferred)

        // Per-class output uses the dirty-only set (KSP backs up unchanged outputs itself).
        val dirtySubclasses: Set<String> = resolver.getSymbolsWithAnnotation(SERIALIZABLE_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.OBJECT }
            .filter { !it.modifiers.contains(Modifier.ABSTRACT) && !it.modifiers.contains(Modifier.SEALED) }
            .filter { it.validate() }
            .mapNotNull { it.qualifiedName?.asString() }
            .toSet()

        val moduleSuffix = environment.options["krig.generated.module"]
            ?: error(
                "krig-ksp-processor requires the 'krig.generated.module' KSP argument. " +
                        "Apply the `krig-mpp-ksp` convention plugin which sets it automatically.",
            )
        val outputPackage = "space.kscience.krig.generated.$moduleSuffix"

        var discoveredThisRound = false
        for ((baseFqn, subclasses) in allSubclasses.toSortedMap()) {
            for (cls in subclasses.sortedBy { it.qualifiedName?.asString() }) {
                val ref = computeContributorRef(baseFqn, cls) ?: continue
                if (ref.key !in collectedContributors) {
                    collectedContributors[ref.key] = ref
                    discoveredThisRound = true
                }
                val clsFqn = cls.qualifiedName?.asString() ?: continue
                // Emit per-class output only for dirty subclasses; KSP restores clean ones
                // from the previous session. The set guards against multi-round dupes.
                if (clsFqn in dirtySubclasses && emittedContributors.add(clsFqn)) {
                    emitContributor(outputPackage, baseFqn, cls, ref)
                }
            }
        }

        // Wait one quiet round before writing the aggregating index. This lets
        // generated/deferred serializers from earlier rounds join the registry.
        if (!emittedIndex && deferred.isEmpty() && !discoveredThisRound && collectedContributors.isNotEmpty()) {
            val filesByPath = resolver.getAllFiles().associateBy { it.filePath }
            val allContainingFiles = collectedContributors.values
                .mapNotNull { filesByPath[it.sourceFilePath] }
                .toTypedArray()
            emitIndex(outputPackage, collectedContributors.values.toList(), allContainingFiles)
            emittedIndex = true
        }

        if (allSubclasses.isEmpty() && collectedContributors.isEmpty()) {
            environment.logger.info("SerializersModuleGenerator: no polymorphic subclasses found.")
        }

        return deferred
    }

    /**
     * Walks every file in the compilation unit and groups concrete `@Serializable` classes
     * by the FQN of their `@PolymorphicBase` supertype. Used to build the full registry for
     * the aggregating index regardless of incremental dirtiness.
     */
    private fun collectAllSubclasses(
        resolver: Resolver,
        baseInterfaces: Map<String, KSClassDeclaration>,
        deferred: MutableList<KSAnnotated>,
    ): Map<String, List<KSClassDeclaration>> {
        if (baseInterfaces.isEmpty()) return emptyMap()
        val registrations = mutableMapOf<String, MutableList<KSClassDeclaration>>()
        for (decl in resolver.getSymbolsWithAnnotation(SERIALIZABLE_FQN).filterIsInstance<KSClassDeclaration>()) {
            if (!decl.validate()) { deferred += decl; continue }
            if (decl.classKind != ClassKind.CLASS && decl.classKind != ClassKind.OBJECT) continue
            if (decl.modifiers.contains(Modifier.ABSTRACT)) continue
            if (decl.modifiers.contains(Modifier.SEALED)) continue
            for (superType in decl.superTypes) {
                val superFqn = superType.resolve().declaration.qualifiedName?.asString() ?: continue
                if (superFqn in baseInterfaces) {
                    registrations.getOrPut(superFqn) { mutableListOf() }.add(decl)
                }
            }
        }
        return registrations
    }

    /** Computes a [ContributorRef] for [cls]; null only when the class lacks a qualifiedName. */
    private fun computeContributorRef(
        baseFqn: String,
        cls: KSClassDeclaration,
    ): ContributorRef? {
        val clsFqn = cls.qualifiedName?.asString() ?: return null
        val sourceFilePath = cls.containingFile?.filePath ?: return null
        val clsSimple = clsFqn.substringAfterLast('.')
        val suffix = stableSuffix(clsFqn)
        val contributorName = "${clsSimple}_${suffix}_Contributor"
        // Disambiguate top-level / nested duplicates by hashing the FQN into the file name.
        val fileName = contributorName
        return ContributorRef(
            valueName = contributorName,
            fileName = fileName,
            baseFqn = baseFqn,
            clsFqn = clsFqn,
            sourceFilePath = sourceFilePath,
        )
    }

    /** Writes one `<SubclassName>_<hash>_Contributor.kt` with `aggregating = false`. */
    private fun emitContributor(
        outputPackage: String,
        baseFqn: String,
        cls: KSClassDeclaration,
        ref: ContributorRef,
    ) {
        val clsFqn = ref.clsFqn
        val clsSimple = ref.clsSimpleName
        val baseSimple = baseFqn.substringAfterLast('.')
        val containingFile = cls.containingFile ?: run {
            environment.logger.warn(
                "SerializersModuleGenerator: '$clsFqn' has no containing file; skipping emission.",
            )
            return
        }

        val text = generatedKotlinFile(
            outputPackage = outputPackage,
            imports = listOf(
                "kotlinx.serialization.modules.SerializersModule",
                "kotlinx.serialization.modules.polymorphic",
                "kotlinx.serialization.modules.subclass",
                baseFqn,
                clsFqn,
            ),
        ) {
            appendLine("/** Polymorphic registration of [$clsSimple] under [$baseSimple]. */")
            appendLine("internal val ${ref.valueName}: SerializersModule = SerializersModule {")
            appendLine("    polymorphic($baseSimple::class) {")
            appendLine("        subclass($clsSimple::class)")
            appendLine("    }")
            appendLine("}")
        }

        val file = environment.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, containingFile),
            packageName = outputPackage,
            fileName = ref.fileName,
        )
        file.write(text.toByteArray())
        file.close()
    }

    /** Emits the aggregating `GeneratedKrigSerializersModule.kt` index. */
    private fun emitIndex(
        outputPackage: String,
        contributors: List<ContributorRef>,
        allContainingFiles: Array<KSFile>,
    ) {
        val text = generatedKotlinFile(
            outputPackage = outputPackage,
            imports = listOf("kotlinx.serialization.modules.SerializersModule"),
        ) {
            appendLine("/**")
            appendLine(" * Aggregated [SerializersModule] of every `@PolymorphicBase` subclass discovered in this")
            appendLine(" * compilation unit. Composed from per-class contributor files generated by KSP.")
            appendLine(" */")
            appendLine("public val generatedKrigSerializersModule: SerializersModule = SerializersModule {")
            for (ref in contributors.sortedBy { it.valueName }) {
                appendLine("    include(${ref.valueName})")
            }
            appendLine("}")
        }

        val file = environment.codeGenerator.createNewFile(
            // aggregating = true: index regenerates whenever any source class set changes;
            // body is just a list of names, so the cost per regeneration is negligible.
            dependencies = Dependencies(aggregating = true, *allContainingFiles),
            packageName = outputPackage,
            fileName = "GeneratedKrigSerializersModule",
        )
        file.write(text.toByteArray())
        file.close()
    }

    private fun generatedKotlinFile(
        outputPackage: String,
        imports: List<String>,
        body: StringBuilder.() -> Unit,
    ): String = buildString {
        appendLine("// Generated by krig-ksp-processor — do not edit")
        appendLine("@file:Suppress(\"unused\")")
        appendLine()
        appendLine("package $outputPackage")
        appendLine()
        imports.forEach { appendLine("import $it") }
        appendLine()
        body()
    }

    /** Stable 6-hex-digit suffix from FQN to disambiguate shadowing class names. */
    private fun stableSuffix(fqn: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in fqn) {
            h = h xor c.code
            h *= 0x01000193
        }
        val masked = (h.toUInt() and 0xFFFFFFu).toString(16)
        return masked.padStart(6, '0')
    }

    private data class ContributorRef(
        val valueName: String,
        val fileName: String,
        val baseFqn: String,
        val clsFqn: String,
        val sourceFilePath: String,
    ) {
        val key: String get() = "$baseFqn::$clsFqn"
        val clsSimpleName: String get() = clsFqn.substringAfterLast('.')
    }
}
