package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate

/**
 * Emits `Merged<Kind>Plugin` for each target id discovered on symbols annotated with
 * `@Contributes(anchor)`. Target id is the `@TargetId` value on the anchor (or its
 * companion); a missing `@TargetId` is a compile error.
 */
internal class ContributesAggregator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    private companion object {
        const val CONTRIBUTES_FQN = "space.kscience.krig.api.annotations.Contributes"
        const val TARGET_ID_FQN = "space.kscience.krig.api.discovery.TargetId"
        const val CONTRIBUTES_MANIFEST_FQN = "space.kscience.krig.assembly.ContributesManifest"
        const val GENERATED_PACKAGE_ROOT = "space.kscience.krig.generated"
    }

    /** First-round latch: aggregating files are emitted once per compilation. */
    private var emitted = false

    /** Contributor entry as seen by the aggregator — carries the symbol + optional explicit id. */
    private data class ContributorEntry(
        val decl: KSClassDeclaration,
        /** Manifest-level override used as the `Name` key when present (from `@ContributesManifest.manifestId`). */
        val manifestId: String?,
        /** Emission strategy: direct reference or invoke-as-factory. */
        val invokeAsFactory: Boolean,
    )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (emitted) return emptyList()

        val moduleSuffix = environment.options["krig.generated.module"]
            ?: error(
                "krig-ksp-processor requires the 'krig.generated.module' KSP argument. " +
                        "Apply the `krig-mpp-ksp` convention plugin which sets it automatically.",
            )
        val generatedPackage = "$GENERATED_PACKAGE_ROOT.$moduleSuffix"

        val deferred = mutableListOf<KSAnnotated>()
        val bucketsByTarget: MutableMap<String, MutableList<ContributorEntry>> = mutableMapOf()

        for (decl in resolver.getSymbolsWithAnnotation(CONTRIBUTES_FQN).filterIsInstance<KSClassDeclaration>()) {
            if (!decl.validate()) { deferred += decl; continue }
            if (decl.classKind != ClassKind.OBJECT) continue
            val targets = decl.resolveContributes() ?: continue
            for ((targetId, entry) in targets) {
                bucketsByTarget.getOrPut(targetId) { mutableListOf() } += entry
            }
        }

        // Uniqueness of Manifest ids within a target (per-module).
        for ((targetId, entries) in bucketsByTarget) {
            val duplicateIds = entries
                .mapNotNull { it.manifestId }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
            if (duplicateIds.isNotEmpty()) {
                environment.logger.error(
                    "Duplicate Manifest ids on target '$targetId': ${duplicateIds.keys.joinToString()}",
                )
            }
        }

        for ((targetId, entries) in bucketsByTarget) {
            emitPlugin(generatedPackage, moduleSuffix, targetId, entries)
        }
        emitted = true

        return deferred
    }

    /**
     * Returns a map of target-id → entry for this symbol. A single symbol may contribute
     * to multiple targets through distinct aliases; entries with the same target are
     * deduped by declaration.
     */
    private fun KSClassDeclaration.resolveContributes(): Map<String, ContributorEntry>? {
        val found = mutableMapOf<String, ContributorEntry>()
        for (ann in annotations) {
            val annDecl = (ann.annotationType.resolve().declaration as? KSClassDeclaration) ?: continue
            val annFqn = annDecl.qualifiedName?.asString() ?: continue

            // Direct usage: `@Contributes(Anchor::class) class Foo`.
            if (annFqn == CONTRIBUTES_FQN) {
                val (id, strategy) = readContributes(ann) ?: continue
                found[id] = ContributorEntry(
                    decl = this,
                    manifestId = readManifestId(annotations),
                    invokeAsFactory = strategy,
                )
                continue
            }

            // Alias: walk the annotation's meta-annotations looking for @Contributes.
            for (meta in annDecl.annotations) {
                val metaFqn = (meta.annotationType.resolve().declaration as? KSClassDeclaration)
                    ?.qualifiedName?.asString() ?: continue
                if (metaFqn != CONTRIBUTES_FQN) continue
                val (id, strategy) = readContributes(meta) ?: continue
                val manifestId = if (annFqn == CONTRIBUTES_MANIFEST_FQN) readManifestIdArg(ann) else null
                found[id] = ContributorEntry(
                    decl = this,
                    manifestId = manifestId,
                    invokeAsFactory = strategy,
                )
            }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /** Returns (target-id, invokeAsFactory) or null if the anchor is malformed. */
    private fun readContributes(contributesAnnotation: KSAnnotation): Pair<String, Boolean>? {
        val anchorType = contributesAnnotation.arguments
            .firstOrNull { it.name?.asString() == "anchor" }
            ?.value as? KSType
            ?: run {
                environment.logger.error("@Contributes is missing its `anchor` argument")
                return null
            }
        val anchorDecl = anchorType.declaration as? KSClassDeclaration ?: return null
        val targetId = readTargetIdOnAnchor(anchorDecl) ?: run {
            environment.logger.error(
                "@Contributes anchor ${anchorDecl.qualifiedName?.asString()} is missing " +
                        "`@TargetId(\"...\")`. Every anchor — object or companion — must declare " +
                        "its stable wire target id via the TargetId annotation.",
                anchorDecl,
            )
            return null
        }
        val invokeAsFactory = contributesAnnotation.arguments
            .firstOrNull { it.name?.asString() == "strategy" }
            ?.value
            ?.toString()
            ?.endsWith("INVOKE_AS_FACTORY") == true
        return targetId to invokeAsFactory
    }

    /**
     * Looks for `@TargetId` directly on the anchor (for `object` anchors) or on the
     * anchor's companion (for `class` anchors). Returns the literal `value` argument.
     */
    private fun readTargetIdOnAnchor(anchor: KSClassDeclaration): String? {
        anchor.annotations
            .firstOrNull { annFqn(it) == TARGET_ID_FQN }
            ?.let { return readTargetIdValue(it) }
        val companion = anchor.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?: return null
        return companion.annotations
            .firstOrNull { annFqn(it) == TARGET_ID_FQN }
            ?.let { readTargetIdValue(it) }
    }

    private fun readTargetIdValue(targetIdAnnotation: KSAnnotation): String? =
        targetIdAnnotation.arguments
            .firstOrNull { it.name?.asString() == "value" }
            ?.value as? String

    private fun annFqn(a: KSAnnotation): String? =
        (a.annotationType.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()

    /** `manifestId` argument of `@ContributesManifest`, or null if absent. */
    private fun readManifestId(annotations: Sequence<KSAnnotation>): String? {
        val manifestAnnotation = annotations.firstOrNull { annFqn(it) == CONTRIBUTES_MANIFEST_FQN } ?: return null
        return readManifestIdArg(manifestAnnotation)
    }

    private fun readManifestIdArg(ann: KSAnnotation): String? =
        ann.arguments
            .firstOrNull { it.name?.asString() == "manifestId" }
            ?.value as? String

    /** Derives a Pascal-cased plugin name segment from a target id. */
    private fun pluginNameSegment(targetId: String): String {
        val suffix = targetId.substringAfterLast('.', targetId)
        val pascal = suffix.split('-').joinToString("") { part ->
            part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        }
        return pluralize(pascal)
    }

    /**
     * Ensure the generated plugin class name sounds natural. Covers the cases that appear
     * in control-domain vocabulary without trying to be a full morphology library:
     *  - already plural (ends in `s`)          — keep
     *  - consonant + `y`                        — `y` → `ies`   (Factory, Policy, Proxy)
     *  - otherwise                              — + `s`         (PipelineFeatureSpec, Handler, Recovery in kebab → "Recovery" + "s")
     */
    private fun pluralize(word: String): String = when {
        word.endsWith("s") -> word
        word.length >= 2 && word.endsWith("y") && word[word.length - 2] !in "aeiouAEIOU" ->
            word.dropLast(1) + "ies"
        else -> word + "s"
    }

    private fun pluginKindSegment(targetId: String): String =
        targetId.substringAfterLast('.', targetId)

    /** Emits the `Merged<Kind>Plugin` for one (module × target-id) tuple. */
    private fun emitPlugin(
        generatedPackage: String,
        moduleSuffix: String,
        targetId: String,
        contributors: List<ContributorEntry>,
    ) {
        val pluginName = "Merged${pluginNameSegment(targetId)}Plugin"
        val tagName = "krig.generated.$moduleSuffix.${pluginKindSegment(targetId)}"
        val containingFiles = contributors.mapNotNull { it.decl.containingFile }.toTypedArray()
        val file = environment.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *containingFiles),
            packageName = generatedPackage,
            fileName = pluginName,
        )

        val source = buildString {
            appendLine("// Generated by krig-ksp-processor — do not edit by hand.")
            appendLine("@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")")
            appendLine()
            appendLine("package $generatedPackage")
            appendLine()
            appendLine("import space.kscience.dataforge.context.AbstractPlugin")
            appendLine("import space.kscience.dataforge.context.Context")
            appendLine("import space.kscience.dataforge.context.PluginFactory")
            appendLine("import space.kscience.dataforge.context.PluginTag")
            appendLine("import space.kscience.dataforge.meta.Meta")
            appendLine("import space.kscience.dataforge.names.Name")
            appendLine("import space.kscience.dataforge.names.parseAsName")
            appendLine()
            appendLine("/**")
            appendLine(" * KSP-generated DataForge plugin for target `$targetId`.")
            appendLine(" * Contributors: ${contributors.size}")
            appendLine(" */")
            appendLine("public class $pluginName(meta: Meta = Meta.EMPTY) : AbstractPlugin(meta) {")
            appendLine("    override val tag: PluginTag get() = Companion.tag")
            appendLine()
            appendLine("    override fun content(target: String): Map<Name, Any> = when (target) {")
            appendLine("        TARGET -> entries")
            appendLine("        else -> emptyMap()")
            appendLine("    }")
            appendLine()
            appendLine("    public companion object : PluginFactory<$pluginName> {")
            appendLine("        public const val TARGET: String = \"$targetId\"")
            appendLine()
            appendLine("        override val tag: PluginTag = PluginTag(\"$tagName\", PluginTag.DATAFORGE_GROUP)")
            appendLine()
            appendLine("        override fun build(context: Context, meta: Meta): $pluginName = $pluginName(meta)")
            appendLine()
            appendLine("        public val entries: Map<Name, Any> = mapOf(")
            for (entry in contributors) {
                val fqn = entry.decl.qualifiedName?.asString() ?: continue
                val keyLiteral = entry.manifestId ?: entry.decl.simpleName.asString()
                val emit = if (entry.invokeAsFactory) "$fqn()" else fqn
                appendLine("            \"$keyLiteral\".parseAsName() to $emit,")
            }
            appendLine("        )")
            appendLine("    }")
            appendLine("}")
            appendLine()
        }

        file.write(source.toByteArray(Charsets.UTF_8))
        file.close()

        environment.logger.info(
            "ContributesAggregator: emitted $generatedPackage.$pluginName (target=$targetId, " +
                    "contributors=${contributors.size}).",
        )
    }
}
