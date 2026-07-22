package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/**
 * Emits the explicitly named `Merged<generatedName>Plugin` for each target id discovered on symbols annotated with
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
        private val ABSTRACT_PLUGIN = ClassName("space.kscience.dataforge.context", "AbstractPlugin")
        private val CONTEXT = ClassName("space.kscience.dataforge.context", "Context")
        private val PLUGIN_FACTORY = ClassName("space.kscience.dataforge.context", "PluginFactory")
        private val PLUGIN_TAG = ClassName("space.kscience.dataforge.context", "PluginTag")
        private val META = ClassName("space.kscience.dataforge.meta", "Meta")
        private val NAME = ClassName("space.kscience.dataforge.names", "Name")
        private val MAP = ClassName("kotlin.collections", "Map")
        private val PARSE_AS_NAME = MemberName("space.kscience.dataforge.names", "parseAsName")

        private val TARGET_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
        private val GENERATED_NAME_PATTERN = Regex("[A-Z][A-Za-z0-9]{0,63}")
        private val MANIFEST_ID_PATTERN = Regex("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*")
        private val DEFAULT_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    private val expectedSupertypeByAliasFqn: Map<String, String> = mapOf(
        CONTRIBUTES_FACTORY_FQN to DEVICE_FACTORY_FQN,
        CONTRIBUTES_PIPELINE_FEATURE_FQN to PIPELINE_FEATURE_FQN,
    )

    /** Round-local contributor entry used only while KSP symbols are current. */
    private data class ResolvedContributorEntry(
        val decl: KSClassDeclaration,
        /** Annotation used on [decl], either direct `@Contributes` or a typed alias. */
        val annotationFqn: String,
        /** Manifest-level override used as the `Name` key when present (from `@ContributesManifest.manifestId`). */
        val manifestId: String?,
        /** Emission strategy: direct reference or invoke-as-factory. */
        val invokeAsFactory: Boolean,
    )

    /** Cross-round state deliberately contains no KSP symbols. */
    private data class ContributorRef(
        val declarationFqn: String,
        val simpleName: String,
        val declarationPackageName: String,
        val declarationSimpleNames: List<String>,
        val sourceFilePath: String,
        val annotationFqn: String,
        val manifestId: String?,
        val invokeAsFactory: Boolean,
    ) {
        val effectiveKey: String get() = manifestId ?: simpleName
    }

    private data class ContributionTargetSpec(
        val id: String,
        val generatedName: String,
    )

    private var generatedPackage: String? = null
    private var moduleSuffix: String? = null
    private val contributorsByTarget: MutableMap<String, MutableMap<String, ContributorRef>> = linkedMapOf()
    private val targetSpecsById: MutableMap<String, ContributionTargetSpec> = linkedMapOf()
    /** Replaced at the start of every round and consumed only by [finish]. */
    private var lastSourceFilesByPath: Map<String, KSFile> = emptyMap()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val namespace = environment.requireGeneratedNamespace()
        this.moduleSuffix = namespace.moduleSuffix
        this.generatedPackage = namespace.packageName

        val sourceFiles = resolver.getAllFiles().toList()
        lastSourceFilesByPath = sourceFiles.associateBy { it.filePath }

        val deferred = mutableListOf<KSAnnotated>()
        val bucketsByTarget: MutableMap<String, MutableList<ResolvedContributorEntry>> = mutableMapOf()
        val metaAnnotationsByAliasFqn = mutableMapOf<String, List<KSAnnotation>>()

        // The plugin is an aggregating output. getSymbolsWithAnnotation is dirty-only in
        // incremental rounds, so discover contributors from every current source declaration.
        for (decl in sourceFiles.getAllClassDeclarations()) {
            if (decl.classKind == ClassKind.ANNOTATION_CLASS) {
                // Alias annotations such as `@ContributesManifest` are meta-annotated with
                // `@Contributes`; they are not contributor objects themselves.
                continue
            }
            val deferredBeforeAnnotations = deferred.size
            val targets = decl.resolveContributes(deferred, metaAnnotationsByAliasFqn) ?: continue
            if (deferred.size != deferredBeforeAnnotations) continue
            if (!decl.validate()) {
                deferred.addOnce(decl)
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
            if (!decl.isAccessibleFromGeneratedCode()) {
                environment.logger.error(
                    "ContributesAggregator cannot reference private/protected contributor " +
                        "${decl.qualifiedName?.asString()} from generated code.",
                    decl,
                )
                continue
            }
            for ((targetId, entry) in targets) {
                bucketsByTarget.getOrPut(targetId) { mutableListOf() } += entry
            }
        }
        val validBucketsByTarget = linkedMapOf<String, MutableList<ResolvedContributorEntry>>()
        for ((targetId, entries) in bucketsByTarget) {
            for (entry in entries) {
                when (validateContributorShape(resolver, entry, deferred)) {
                    ValidationResult.Valid -> validBucketsByTarget.getOrPut(targetId) { mutableListOf() } += entry
                    ValidationResult.Invalid -> continue
                    ValidationResult.Deferred -> continue
                }
            }
        }
        for ((targetId, entries) in validBucketsByTarget) {
            val storedByDeclaration = contributorsByTarget.getOrPut(targetId) { linkedMapOf() }
            for (entry in entries) {
                val ref = entry.toRef() ?: continue
                val previous = storedByDeclaration.putIfAbsent(ref.declarationFqn, ref)
                if (previous != null && previous != ref) {
                    environment.logger.error(
                        "Conflicting @Contributes declarations for target '$targetId' and " +
                            "${ref.declarationFqn} across KSP rounds.",
                        entry.decl,
                    )
                }
            }
        }

        return deferred
    }

    override fun finish() {
        val generatedPackage = generatedPackage ?: return
        val moduleSuffix = moduleSuffix ?: return
        val generatedNameCollisions = targetSpecsById.values
            .groupBy { it.generatedName }
            .filterValues { specs -> specs.map { it.id }.distinct().size > 1 }
        if (generatedNameCollisions.isNotEmpty()) {
            environment.logger.error(
                "Duplicate TargetId.generatedName values: " + generatedNameCollisions.entries
                    .sortedBy { it.key }
                    .joinToString { (name, specs) -> "$name -> ${specs.map { it.id }.sorted().joinToString()}" },
            )
            return
        }
        for ((targetId, refsByDeclaration) in contributorsByTarget.toSortedMap()) {
            val targetSpec = targetSpecsById[targetId] ?: run {
                environment.logger.error("ContributesAggregator lost TargetId metadata for '$targetId'.")
                continue
            }
            val contributors = refsByDeclaration.values.sortedBy { it.declarationFqn }
            val duplicateKeys = contributors
                .groupingBy { it.effectiveKey }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateKeys.isNotEmpty()) {
                environment.logger.error(
                    "Duplicate contribution keys on target '$targetId': ${duplicateKeys.sorted().joinToString()}",
                )
                continue
            }
            val missingSourcePaths = contributors
                .map { it.sourceFilePath }
                .filterNot(lastSourceFilesByPath::containsKey)
                .distinct()
            if (missingSourcePaths.isNotEmpty()) {
                environment.logger.error(
                    "ContributesAggregator lost current KSP source origins before finish: " +
                        missingSourcePaths.joinToString(),
                )
                continue
            }
            val containingFiles = contributors
                .mapNotNull { lastSourceFilesByPath[it.sourceFilePath] }
                .distinctBy { it.filePath }
                .toTypedArray()
            emitPlugin(generatedPackage, moduleSuffix, targetSpec, contributors, containingFiles)
        }
    }

    override fun onError() {
        contributorsByTarget.clear()
        targetSpecsById.clear()
        lastSourceFilesByPath = emptyMap()
    }

    private fun ResolvedContributorEntry.toRef(): ContributorRef? {
        val declarationFqn = decl.qualifiedName?.asString() ?: return null
        val sourceFilePath = decl.containingFile?.filePath ?: return null
        val declarationSimpleName = decl.simpleName.asString()
        if (manifestId != null && (manifestId.length > 128 || !MANIFEST_ID_PATTERN.matches(manifestId))) {
            environment.logger.error(
                "ContributesManifest.manifestId '$manifestId' must be at most 128 characters and match " +
                    "${MANIFEST_ID_PATTERN.pattern}.",
                decl,
            )
            return null
        }
        if (manifestId == null && !DEFAULT_KEY_PATTERN.matches(declarationSimpleName)) {
            environment.logger.error(
                "Contributor ${decl.qualifiedName?.asString()} needs a canonical generated key; " +
                    "its simple name must match ${DEFAULT_KEY_PATTERN.pattern}.",
                decl,
            )
            return null
        }
        val declarationSimpleNames = generateSequence(decl) { current ->
            current.parentDeclaration as? KSClassDeclaration
        }.map { it.simpleName.asString() }.toList().asReversed()
        return ContributorRef(
            declarationFqn = declarationFqn,
            simpleName = declarationSimpleName,
            declarationPackageName = decl.packageName.asString(),
            declarationSimpleNames = declarationSimpleNames,
            sourceFilePath = sourceFilePath,
            annotationFqn = annotationFqn,
            manifestId = manifestId,
            invokeAsFactory = invokeAsFactory,
        )
    }

    /**
     * Returns a map of target-id → entry for this symbol. A single symbol may contribute
     * to multiple targets through distinct aliases; entries with the same target are
     * deduped by declaration.
     */
    private fun KSClassDeclaration.resolveContributes(
        deferred: MutableList<KSAnnotated>,
        metaAnnotationsByAliasFqn: MutableMap<String, List<KSAnnotation>>,
    ): Map<String, ResolvedContributorEntry>? {
        val found = mutableMapOf<String, ResolvedContributorEntry>()
        for (ann in annotations) {
            val annDecl = ann.annotationType.safeResolve(this, deferred)?.declaration?.actualClassDeclaration()
                ?: continue
            val annFqn = annDecl.qualifiedName?.asString() ?: continue

            // Direct usage: `@Contributes(Anchor::class) class Foo`.
            if (annFqn == CONTRIBUTES_FQN) {
                val (target, strategy) = readContributes(ann, this, deferred) ?: continue
                if (!registerTarget(target, this)) continue
                found.addContribution(target.id, ResolvedContributorEntry(
                    decl = this,
                    annotationFqn = CONTRIBUTES_FQN,
                    manifestId = readManifestId(annotations, this, deferred),
                    invokeAsFactory = strategy,
                ), this)
                continue
            }

            // Alias: walk the annotation's meta-annotations looking for @Contributes.
            val metaAnnotations = metaAnnotationsByAliasFqn[annFqn] ?: run {
                val deferredBeforeMeta = deferred.size
                val resolved = annDecl.annotations.filter { meta ->
                    val metaFqn = meta.annotationType.safeResolve(this, deferred)
                        ?.declaration
                        ?.actualClassDeclaration()
                        ?.qualifiedName
                        ?.asString()
                    metaFqn == CONTRIBUTES_FQN
                }.toList()
                if (deferred.size == deferredBeforeMeta) metaAnnotationsByAliasFqn[annFqn] = resolved
                resolved
            }
            for (meta in metaAnnotations) {
                val (target, strategy) = readContributes(meta, this, deferred) ?: continue
                if (!registerTarget(target, this)) continue
                val manifestId = if (annFqn == CONTRIBUTES_MANIFEST_FQN) readManifestIdArg(ann) else null
                found.addContribution(target.id, ResolvedContributorEntry(
                    decl = this,
                    annotationFqn = annFqn,
                    manifestId = manifestId,
                    invokeAsFactory = strategy,
                ), this)
            }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    private fun registerTarget(target: ContributionTargetSpec, owner: KSClassDeclaration): Boolean {
        val previous = targetSpecsById.putIfAbsent(target.id, target)
        if (previous == null || previous == target) return true
        environment.logger.error(
            "Target id '${target.id}' declares conflicting generated names " +
                "'${previous.generatedName}' and '${target.generatedName}'.",
            owner,
        )
        return false
    }

    private fun MutableMap<String, ResolvedContributorEntry>.addContribution(
        targetId: String,
        entry: ResolvedContributorEntry,
        owner: KSClassDeclaration,
    ) {
        val previous = putIfAbsent(targetId, entry)
        if (previous != null) {
            environment.logger.error(
                "${owner.qualifiedName?.asString()} contributes to target '$targetId' more than once.",
                owner,
            )
        }
    }

    /** Returns (target identity, invokeAsFactory) or null if the anchor is malformed. */
    private fun readContributes(
        contributesAnnotation: KSAnnotation,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Pair<ContributionTargetSpec, Boolean>? {
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
        val anchorDecl = anchorType.declaration.actualClassDeclaration() ?: return null
        val deferredBeforeTargetId = deferred.size
        val target = readTargetIdOnAnchor(anchorDecl, owner, deferred)
        if (deferred.size != deferredBeforeTargetId) return null
        if (target == null) {
            environment.logger.error(
                "@Contributes anchor ${anchorDecl.qualifiedName?.asString()} is missing " +
                        "`@TargetId(value = \"...\", generatedName = \"...\")`. Every anchor — object or companion — " +
                        "must declare its stable wire id and generated Kotlin name.",
                anchorDecl,
            )
            return null
        }
        val invokeAsFactory = contributesAnnotation.arguments
            .firstOrNull { it.name?.asString() == "strategy" }
            ?.value
            ?.toString()
            ?.endsWith("INVOKE_AS_FACTORY") == true
        return target to invokeAsFactory
    }

    /**
     * Looks for `@TargetId` directly on the anchor (for `object` anchors) or on the
     * anchor's companion (for `class` anchors). Returns the validated wire/code identity.
     */
    private fun readTargetIdOnAnchor(
        anchor: KSClassDeclaration,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): ContributionTargetSpec? {
        anchor.annotations
            .firstOrNull { annFqn(it, owner, deferred) == TARGET_ID_FQN }
            ?.let { return readTargetSpec(it) }
        val companion = anchor.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?: return null
        return companion.annotations
            .firstOrNull { annFqn(it, owner, deferred) == TARGET_ID_FQN }
            ?.let { readTargetSpec(it) }
    }

    private fun readTargetSpec(targetIdAnnotation: KSAnnotation): ContributionTargetSpec? {
        val id = targetIdAnnotation.arguments
            .firstOrNull { it.name?.asString() == "value" }
            ?.value as? String
            ?: return null
        val generatedName = targetIdAnnotation.arguments
            .firstOrNull { it.name?.asString() == "generatedName" }
            ?.value as? String
            ?: return null
        if (id.length > 128 || !TARGET_ID_PATTERN.matches(id)) {
            environment.logger.error(
                "TargetId.value '$id' must be at most 128 characters and match ${TARGET_ID_PATTERN.pattern}.",
                targetIdAnnotation,
            )
            return null
        }
        if (!GENERATED_NAME_PATTERN.matches(generatedName)) {
            environment.logger.error(
                "TargetId.generatedName '$generatedName' must match ${GENERATED_NAME_PATTERN.pattern}.",
                targetIdAnnotation,
            )
            return null
        }
        return ContributionTargetSpec(id, generatedName)
    }

    private fun annFqn(
        annotation: KSAnnotation,
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? = annotation.annotationType.safeResolve(owner, deferred)
        ?.declaration
        ?.actualClassDeclaration()
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
        entry: ResolvedContributorEntry,
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
        entry: ResolvedContributorEntry,
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

    /** Emits the `Merged<Kind>Plugin` for one (module × target-id) tuple. */
    private fun emitPlugin(
        generatedPackage: String,
        moduleSuffix: String,
        target: ContributionTargetSpec,
        contributors: List<ContributorRef>,
        containingFiles: Array<KSFile>,
    ) {
        val targetId = target.id
        val pluginName = "Merged${target.generatedName}Plugin"
        val tagName = "krig.generated.$moduleSuffix.target.$targetId"
        val file = environment.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *containingFiles),
            packageName = generatedPackage,
            fileName = pluginName,
        )

        val pluginType = ClassName(generatedPackage, pluginName)
        val entriesType = MAP.parameterizedBy(NAME, ClassName("kotlin", "Any"))
        val entriesInitializer = CodeBlock.builder().add("mapOf(\n").indent()
        for (entry in contributors) {
            val declarationType = ClassName(
                entry.declarationPackageName,
                entry.declarationSimpleNames.first(),
                *entry.declarationSimpleNames.drop(1).toTypedArray(),
            )
            val parsedKey = CodeBlock.of("%S.%M()", entry.effectiveKey, PARSE_AS_NAME)
            entriesInitializer.add("%L to ", parsedKey)
            if (entry.invokeAsFactory) {
                if (entry.manifestId != null) {
                    entriesInitializer.add(
                        "%T().also { manifest -> require(manifest.id == %L) { %S } }",
                        declarationType,
                        parsedKey,
                        "ContributesManifest '${entry.effectiveKey}' produced a manifest with a different id.",
                    )
                } else {
                    entriesInitializer.add("%T()", declarationType)
                }
            } else {
                entriesInitializer.add("%T", declarationType)
            }
            entriesInitializer.add(",\n")
        }
        entriesInitializer.unindent().add(")")

        val companion = TypeSpec.companionObjectBuilder()
            .addSuperinterface(PLUGIN_FACTORY.parameterizedBy(pluginType))
            .addProperty(
                PropertySpec.builder("TARGET", String::class)
                    .addModifiers(KModifier.PUBLIC, KModifier.CONST)
                    .initializer("%S", targetId)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("tag", PLUGIN_TAG)
                    .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                    .initializer("%T(%S, %T.DATAFORGE_GROUP)", PLUGIN_TAG, tagName, PLUGIN_TAG)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("build")
                    .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                    .addParameter("context", CONTEXT)
                    .addParameter("meta", META)
                    .returns(pluginType)
                    .addStatement("return %T(meta)", pluginType)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("entries", entriesType)
                    .addModifiers(KModifier.PUBLIC)
                    .initializer(entriesInitializer.build())
                    .build(),
            )
            .build()

        val plugin = TypeSpec.classBuilder(pluginName)
            .addModifiers(KModifier.PUBLIC)
            .addKdoc("KSP-generated DataForge plugin for [TARGET].\n\nContributors: %L.\n", contributors.size)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("meta", META)
                            .defaultValue("%T.EMPTY", META)
                            .build(),
                    )
                    .build(),
            )
            .superclass(ABSTRACT_PLUGIN)
            .addSuperclassConstructorParameter("meta")
            .addProperty(
                PropertySpec.builder("tag", PLUGIN_TAG)
                    .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return Companion.tag").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("content")
                    .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                    .addParameter("target", String::class)
                    .returns(entriesType)
                    .beginControlFlow("return when (target)")
                    .addStatement("TARGET -> entries")
                    .addStatement("else -> emptyMap()")
                    .endControlFlow()
                    .build(),
            )
            .addType(companion)
            .build()

        val source = FileSpec.builder(generatedPackage, pluginName)
            .addFileComment("Generated by krig-ksp-processor — do not edit by hand.")
            .addType(plugin)
            .build()
            .toString()

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

private fun KSTypeReference?.safeResolve(
    owner: KSAnnotated,
    deferred: MutableList<KSAnnotated>,
): KSType? {
    val type = this?.resolve() ?: return null
    if (type.isError) {
        deferred.addOnce(owner)
        return null
    }
    return type
}
