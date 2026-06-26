package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * Emits `Merged<Kind>Plugin` for each target id discovered on symbols annotated with
 * `@Contributes(anchor)`. Target id is the `@TargetId` value on the anchor (or its
 * companion); a missing `@TargetId` is a compile error.
 */
internal class ContributesAggregator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    /**
     * Annotation FQNs the aggregator matches by string (KSP processors run on a separate
     * classpath and resolve symbols by name, so they cannot — and idiomatically should not —
     * depend on the runtime artifacts they process). To avoid a *silent* break when these
     * annotations are moved/renamed, [ContributesFqnGuardTest] asserts each constant against
     * the real `::class.qualifiedName`. Keep that guard green before any package moves.
     */
    internal companion object {
        const val CONTRIBUTES_FQN = "space.kscience.krig.api.annotations.Contributes"
        const val TARGET_ID_FQN = "space.kscience.krig.api.discovery.TargetId"
        const val CONTRIBUTES_MANIFEST_FQN = "space.kscience.krig.assembly.ContributesManifest"
        const val CONTRIBUTES_FACTORY_FQN = "space.kscience.krig.assembly.ContributesFactory"
        const val CONTRIBUTES_PIPELINE_FEATURE_FQN = "space.kscience.krig.assembly.ContributesPipelineFeature"
        const val CONTRIBUTES_PROTOCOL_FQN = "space.kscience.krig.assembly.ContributesProtocol"
        const val CONTRIBUTES_ACTION_HANDLER_FQN = "space.kscience.krig.assembly.ContributesActionHandler"
        const val DEVICE_MANIFEST_FQN = "space.kscience.krig.core.contracts.DeviceManifest"
        const val DEVICE_FACTORY_FQN = "space.kscience.krig.api.factory.DeviceFactory"
        const val PIPELINE_FEATURE_FQN = "space.kscience.krig.core.features.PipelineFeature"
        const val GENERATED_PACKAGE_ROOT = "space.kscience.krig.generated"
    }

    private val expectedSupertypeByAliasFqn: Map<String, String> = mapOf(
        CONTRIBUTES_FACTORY_FQN to DEVICE_FACTORY_FQN,
        CONTRIBUTES_PIPELINE_FEATURE_FQN to PIPELINE_FEATURE_FQN,
    )

    private val knownContributorAnnotationFqns: List<String> = listOf(
        CONTRIBUTES_FQN,
        CONTRIBUTES_MANIFEST_FQN,
        CONTRIBUTES_FACTORY_FQN,
        CONTRIBUTES_PIPELINE_FEATURE_FQN,
        CONTRIBUTES_PROTOCOL_FQN,
        CONTRIBUTES_ACTION_HANDLER_FQN,
    )

    /** First-round latch: aggregating files are emitted once per compilation. */
    private var emitted = false

    /** Contributor entry as seen by the aggregator — carries the symbol + optional explicit id. */
    private data class ContributorEntry(
        val decl: KSClassDeclaration,
        /** Annotation used on [decl], either direct `@Contributes` or a typed alias. */
        val annotationFqn: String,
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

        val contributorDeclarations = knownContributorAnnotationFqns
            .asSequence()
            .flatMap { annotationFqn ->
                resolver.getSymbolsWithAnnotation(annotationFqn, inDepth = annotationFqn == CONTRIBUTES_FQN)
            }
            .filterIsInstance<KSClassDeclaration>()
            .distinctBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }

        for (decl in contributorDeclarations) {
            if (!decl.validate()) { deferred += decl; continue }
            if (decl.classKind == ClassKind.ANNOTATION_CLASS) {
                // Alias annotations such as `@ContributesManifest` are meta-annotated with
                // `@Contributes`; they are not contributor objects themselves.
                continue
            }
            if (decl.classKind != ClassKind.OBJECT) {
                environment.logger.error(
                    "@Contributes supports only object declarations; " +
                            "'${decl.qualifiedName?.asString()}' is ${decl.classKind.name.lowercase()}.",
                    decl,
                )
                continue
            }
            val targets = decl.resolveContributes(deferred) ?: continue
            for ((targetId, entry) in targets) {
                bucketsByTarget.getOrPut(targetId) { mutableListOf() } += entry
            }
        }
        if (deferred.isNotEmpty()) return deferred

        val validBucketsByTarget = linkedMapOf<String, MutableList<ContributorEntry>>()
        for ((targetId, entries) in bucketsByTarget) {
            for (entry in entries) {
                when (validateContributorShape(resolver, entry, deferred)) {
                    ValidationResult.Valid -> validBucketsByTarget.getOrPut(targetId) { mutableListOf() } += entry
                    ValidationResult.Invalid -> continue
                    ValidationResult.Deferred -> continue
                }
            }
        }
        if (deferred.isNotEmpty()) return deferred

        // Uniqueness of Manifest ids within a target (per-module).
        for ((targetId, entries) in validBucketsByTarget) {
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

        for ((targetId, entries) in validBucketsByTarget) {
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
    private fun KSClassDeclaration.resolveContributes(
        deferred: MutableList<KSAnnotated>,
    ): Map<String, ContributorEntry>? {
        val found = mutableMapOf<String, ContributorEntry>()
        for (ann in annotations) {
            val annDecl = ann.annotationType.safeResolve(this, deferred)?.declaration as? KSClassDeclaration
                ?: continue
            val annFqn = annDecl.qualifiedName?.asString() ?: continue

            // Direct usage: `@Contributes(Anchor::class) class Foo`.
            if (annFqn == CONTRIBUTES_FQN) {
                val (id, strategy) = readContributes(ann, this, deferred) ?: continue
                found[id] = ContributorEntry(
                    decl = this,
                    annotationFqn = CONTRIBUTES_FQN,
                    manifestId = readManifestId(annotations, this, deferred),
                    invokeAsFactory = strategy,
                )
                continue
            }

            // Alias: walk the annotation's meta-annotations looking for @Contributes.
            for (meta in annDecl.annotations) {
                val metaFqn = meta.annotationType.safeResolve(this, deferred)
                    ?.declaration
                    ?.qualifiedName
                    ?.asString()
                    ?: continue
                if (metaFqn != CONTRIBUTES_FQN) continue
                val (id, strategy) = readContributes(meta, this, deferred) ?: continue
                val manifestId = if (annFqn == CONTRIBUTES_MANIFEST_FQN) readManifestIdArg(ann) else null
                found[id] = ContributorEntry(
                    decl = this,
                    annotationFqn = annFqn,
                    manifestId = manifestId,
                    invokeAsFactory = strategy,
                )
            }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /** Returns (target-id, invokeAsFactory) or null if the anchor is malformed. */
    private fun readContributes(
        contributesAnnotation: KSAnnotation,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Pair<String, Boolean>? {
        val anchorType = contributesAnnotation.arguments
            .firstOrNull { it.name?.asString() == "anchor" }
            ?.value as? KSType
            ?: run {
                environment.logger.error("@Contributes is missing its `anchor` argument")
                return null
            }
        if (anchorType.isError) {
            deferred += owner
            return null
        }
        val anchorDecl = anchorType.declaration as? KSClassDeclaration ?: return null
        val deferredBeforeTargetId = deferred.size
        val targetId = readTargetIdOnAnchor(anchorDecl, owner, deferred)
        if (deferred.size != deferredBeforeTargetId) return null
        if (targetId == null) {
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
    private fun readTargetIdOnAnchor(
        anchor: KSClassDeclaration,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? {
        anchor.annotations
            .firstOrNull { annFqn(it, owner, deferred) == TARGET_ID_FQN }
            ?.let { return readTargetIdValue(it) }
        val companion = anchor.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?: return null
        return companion.annotations
            .firstOrNull { annFqn(it, owner, deferred) == TARGET_ID_FQN }
            ?.let { readTargetIdValue(it) }
    }

    private fun readTargetIdValue(targetIdAnnotation: KSAnnotation): String? =
        targetIdAnnotation.arguments
            .firstOrNull { it.name?.asString() == "value" }
            ?.value as? String

    private fun annFqn(
        annotation: KSAnnotation,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? = annotation.annotationType.safeResolve(owner, deferred)
        ?.declaration
        ?.qualifiedName
        ?.asString()

    /** `manifestId` argument of `@ContributesManifest`, or null if absent. */
    private fun readManifestId(
        annotations: Sequence<KSAnnotation>,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? {
        val manifestAnnotation = annotations.firstOrNull { annotation ->
            annFqn(annotation, owner, deferred) == CONTRIBUTES_MANIFEST_FQN
        } ?: return null
        return readManifestIdArg(manifestAnnotation)
    }

    private fun readManifestIdArg(ann: KSAnnotation): String? =
        ann.arguments
            .firstOrNull { it.name?.asString() == "manifestId" }
            ?.value as? String

    private fun validateContributorShape(
        resolver: Resolver,
        entry: ContributorEntry,
        deferred: MutableList<KSAnnotated>,
    ): ValidationResult {
        if (entry.annotationFqn == CONTRIBUTES_MANIFEST_FQN) {
            return validateManifestFactory(resolver, entry, deferred)
        }
        val expectedFqn = expectedSupertypeByAliasFqn[entry.annotationFqn] ?: return ValidationResult.Valid
        val expectedDeclaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(expectedFqn))
            ?: run {
                environment.logger.error(
                    "KRig KSP processor could not resolve expected type $expectedFqn while validating " +
                        entry.annotationFqn.substringAfterLast('.') + ". Check processor/runtime classpath.",
                    entry.decl,
                )
                return ValidationResult.Invalid
            }
        val expectedType = expectedDeclaration.asStarProjectedType()
        val actualType = entry.decl.asStarProjectedType()
        if (actualType.isError) {
            deferred += entry.decl
            return ValidationResult.Deferred
        }
        if (!expectedType.isAssignableFrom(actualType)) {
            environment.logger.error(
                "${entry.decl.qualifiedName?.asString()} is annotated with ${entry.annotationFqn.substringAfterLast('.')} " +
                    "but does not implement/extend $expectedFqn.",
                entry.decl,
            )
            return ValidationResult.Invalid
        }
        return ValidationResult.Valid
    }

    private fun validateManifestFactory(
        resolver: Resolver,
        entry: ContributorEntry,
        deferred: MutableList<KSAnnotated>,
    ): ValidationResult {
        val expectedDeclaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(DEVICE_MANIFEST_FQN))
            ?: run {
                environment.logger.error(
                    "KRig KSP processor could not resolve expected type $DEVICE_MANIFEST_FQN while validating " +
                        "ContributesManifest. Check processor/runtime classpath.",
                    entry.decl,
                )
                return ValidationResult.Invalid
            }
        val expectedType = expectedDeclaration.asStarProjectedType()
        val invoke = entry.decl.getAllFunctions()
            .firstOrNull { function ->
                function.simpleName.asString() == "invoke" && function.parameters.isEmpty()
            }
        if (invoke == null) {
            environment.logger.error(
                "${entry.decl.qualifiedName?.asString()} is annotated with ContributesManifest " +
                    "but must declare `operator fun invoke(): DeviceManifest`.",
                entry.decl,
            )
            return ValidationResult.Invalid
        }
        if (Modifier.OPERATOR !in invoke.modifiers) {
            environment.logger.error(
                "${entry.decl.qualifiedName?.asString()} is annotated with ContributesManifest " +
                    "but `invoke()` must be declared as `operator fun invoke(): DeviceManifest`.",
                invoke,
            )
            return ValidationResult.Invalid
        }
        val returnType = invoke.returnType.safeResolve(entry.decl, deferred) ?: return ValidationResult.Deferred
        if (!expectedType.isAssignableFrom(returnType)) {
            environment.logger.error(
                "${entry.decl.qualifiedName?.asString()} is annotated with ContributesManifest " +
                    "but `invoke()` returns ${returnType.declaration.qualifiedName?.asString()} instead of $DEVICE_MANIFEST_FQN.",
                invoke,
            )
            return ValidationResult.Invalid
        }
        return ValidationResult.Valid
    }

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
                appendLine("            \"${escapeStringLiteral(keyLiteral)}\".parseAsName() to $emit,")
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

private enum class ValidationResult {
    Valid,
    Invalid,
    Deferred,
}

/** Escapes a value for embedding into a generated Kotlin string literal. */
private fun escapeStringLiteral(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private fun KSTypeReference?.safeResolve(
    owner: KSAnnotated,
    deferred: MutableList<KSAnnotated>,
): KSType? {
    val type = this?.resolve() ?: return null
    if (type.isError) {
        deferred += owner
        return null
    }
    return type
}
