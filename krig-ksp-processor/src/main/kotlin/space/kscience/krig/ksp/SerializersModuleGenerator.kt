package space.kscience.krig.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec

/**
 * Hybrid KSP generator for the krig polymorphic serializers registry.
 *
 * - Per `@Serializable` subclass of an `@PolymorphicBase`-annotated interface emits one
 *   small `<SubclassName>Contributor.kt` file with `Dependencies(aggregating = false,
 *   subclass.containingFile)`. Editing or removing one class invalidates exactly that
 *   one generated file.
 * - One small index `GeneratedKrigSerializersModule.kt` per module aggregates the
 *   per-class contributors via `SerializersModule { include(...) }`. The index is
 *   `aggregating = true`, so KSP rebuilds it when the source universe changes.
 *
 * Downstream modules import the public `generatedKrigSerializersModule` from the
 * generator-emitted package and compose it with their own contributions.
 */
internal class SerializersModuleGenerator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    private companion object {
        const val POLYMORPHIC_BASE_FQN = "space.kscience.krig.api.annotations.PolymorphicBase"
        const val POLYMORPHIC_FQN = "kotlinx.serialization.Polymorphic"
        const val SERIALIZABLE_FQN = "kotlinx.serialization.Serializable"
        const val SERIAL_NAME_FQN = "kotlinx.serialization.SerialName"
        const val TRANSIENT_FQN = "kotlinx.serialization.Transient"
        const val JSON_NAMES_FQN = "kotlinx.serialization.json.JsonNames"
        const val JSON_CLASS_DISCRIMINATOR_FQN = "kotlinx.serialization.json.JsonClassDiscriminator"
        const val KEEP_GENERATED_SERIALIZER_FQN = "kotlinx.serialization.KeepGeneratedSerializer"
        const val META_SERIALIZABLE_FQN = "kotlinx.serialization.MetaSerializable"
        const val DEFAULT_SERIALIZER_FQN = "kotlinx.serialization.KSerializer"
        const val CLASS_DISCRIMINATOR = "type"

        val SERIALIZERS_MODULE = ClassName("kotlinx.serialization.modules", "SerializersModule")
        val POLYMORPHIC = MemberName("kotlinx.serialization.modules", "polymorphic")
        val SUBCLASS = MemberName("kotlinx.serialization.modules", "subclass")
    }

    /** FQNs of subclass contributors already emitted in this compilation. Multi-round dedup. */
    private val emittedContributors: MutableSet<String> = mutableSetOf()
    /** Primitive contributor metadata collected across rounds. Never stores KS symbols. */
    private val collectedContributors: MutableMap<String, ContributorRef> = linkedMapOf()
    private var outputPackage: String? = null
    /** Current last-round symbols; replaced before every process and read only from finish. */
    private var lastSourceFilesByPath: Map<String, KSFile> = emptyMap()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val sourceFiles = resolver.getAllFiles().toList()
        lastSourceFilesByPath = sourceFiles.associateBy { it.filePath }

        // An aggregating index must be rebuilt from KSP's complete dirty processing closure.
        // getSymbolsWithAnnotation is narrower: it is new/deferred-only in later rounds.
        val allSubclasses = collectAllSubclasses(sourceFiles.getAllClassDeclarations(), deferred)

        // Per-class output uses the dirty-only set (KSP backs up unchanged outputs itself).
        val dirtySubclasses: Set<String> = resolver.getSymbolsWithAnnotation(SERIALIZABLE_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.hasGeneratedContributorKind() }
            .filter { !it.modifiers.contains(Modifier.ABSTRACT) && !it.modifiers.contains(Modifier.SEALED) }
            .filter { it.validate() }
            .mapNotNull { it.qualifiedName?.asString() }
            .toSet()

        val outputPackage = environment.requireGeneratedNamespace().packageName
        this.outputPackage = outputPackage

        val baseCountBySubclass = allSubclasses.values
            .flatten()
            .mapNotNull { it.qualifiedName?.asString() }
            .groupingBy { it }
            .eachCount()
        for ((base, subclasses) in allSubclasses.toSortedMap(compareBy(KotlinTypeRef::fqn))) {
            for (cls in subclasses.sortedBy { it.qualifiedName?.asString() }) {
                val clsFqn = cls.qualifiedName?.asString() ?: continue
                val serialName = cls.serialNameOrDefault(clsFqn, cls, deferred) ?: continue
                val ref = computeContributorRef(
                    base = base,
                    cls = cls,
                    serialName = serialName,
                    disambiguateBase = baseCountBySubclass.getValue(clsFqn) > 1,
                ) ?: continue
                val previous = collectedContributors[ref.key]
                if (previous != null && previous != ref) {
                    environment.logger.error(
                        "SerializersModuleGenerator discovered conflicting declarations for ${ref.key}.",
                        cls,
                    )
                    continue
                }
                if (previous == null) {
                    val serialNameOwner = collectedContributors.values.firstOrNull {
                        it.base == ref.base && it.serialName == ref.serialName && it.subtype != ref.subtype
                    }
                    if (serialNameOwner != null) {
                        environment.logger.error(
                            "SerializersModuleGenerator found duplicate serial name '${ref.serialName}' " +
                                "for base ${ref.base.fqn}: ${serialNameOwner.subtype.fqn} and ${ref.subtype.fqn}.",
                            cls,
                        )
                        continue
                    }
                    val nameOwner = collectedContributors.values.firstOrNull {
                        it.valueName == ref.valueName && it.key != ref.key
                    }
                    if (nameOwner != null) {
                        environment.logger.error(
                            "SerializersModuleGenerator generated-name collision between " +
                                "${nameOwner.key} and ${ref.key}: ${ref.valueName}.",
                            cls,
                        )
                        continue
                    }
                    collectedContributors[ref.key] = ref
                }
                // Emit per-class output only for dirty subclasses; KSP restores clean ones
                // from the previous session. The set guards against multi-round dupes.
                if (clsFqn in dirtySubclasses && emittedContributors.add(ref.key)) {
                    emitContributor(outputPackage, cls, ref)
                }
            }
        }

        if (allSubclasses.isEmpty() && collectedContributors.isEmpty()) {
            environment.logger.info("SerializersModuleGenerator: no polymorphic subclasses found.")
        }

        return deferred
    }

    override fun finish() {
        val outputPackage = outputPackage ?: return
        if (collectedContributors.isEmpty()) return
        val missingSourcePaths = collectedContributors.values
            .map { it.sourceFilePath }
            .filterNot(lastSourceFilesByPath::containsKey)
            .distinct()
        if (missingSourcePaths.isNotEmpty()) {
            environment.logger.error(
                "SerializersModuleGenerator lost current KSP source origins before finish: " +
                    missingSourcePaths.joinToString(),
            )
            return
        }
        val dependencies = collectedContributors.values
            .mapNotNull { lastSourceFilesByPath[it.sourceFilePath] }
            .distinctBy { it.filePath }
            .toTypedArray()
        emitIndex(outputPackage, collectedContributors.values.toList(), dependencies)
    }

    override fun onError() {
        collectedContributors.clear()
        lastSourceFilesByPath = emptyMap()
    }

    /**
     * Groups every concrete source `@Serializable` class by all `@PolymorphicBase`
     * supertypes in its hierarchy. Supertypes may come from the compilation classpath,
     * which is the normal extension-module case for a published SDK.
     */
    private fun collectAllSubclasses(
        sourceClasses: Sequence<KSClassDeclaration>,
        deferred: MutableList<KSAnnotated>,
    ): Map<KotlinTypeRef, List<KSClassDeclaration>> {
        val registrations = mutableMapOf<KotlinTypeRef, MutableList<KSClassDeclaration>>()
        for (decl in sourceClasses) {
            val deferredBeforeAnnotations = deferred.size
            val serializableAnnotation = decl.findAnnotation(SERIALIZABLE_FQN, decl, deferred) ?: continue
            if (deferred.size != deferredBeforeAnnotations) continue
            if (!decl.validate()) {
                deferred.addOnce(decl)
                continue
            }
            if (!decl.hasDiscoverableSubtypeKind()) continue
            if (decl.modifiers.contains(Modifier.ABSTRACT)) continue
            if (decl.modifiers.contains(Modifier.SEALED)) continue
            val deferredBeforeSupertypes = deferred.size
            val bases = decl.polymorphicBases(deferred)
            if (deferred.size != deferredBeforeSupertypes) continue
            if (bases.isEmpty()) continue
            val fqn = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
            if (decl.classKind == ClassKind.ENUM_CLASS) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot auto-register enum subtype $fqn because " +
                        "class-discriminator polymorphism requires an object-shaped serializer; " +
                        "use a @Serializable class or object wrapper instead.",
                    decl,
                )
                continue
            }
            if (Modifier.VALUE in decl.modifiers) {
                environment.logger.error(
                    "SerializersModuleGenerator does not auto-register value subtype $fqn because KSP " +
                        "cannot prove its inline serializer is object-shaped; use a @Serializable class/object " +
                        "wrapper or an explicitly configured compatible module and wire format.",
                    decl,
                )
                continue
            }
            if (!decl.isAccessibleFromGeneratedCode()) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot reference private/protected subtype " +
                        "${decl.qualifiedName?.asString()} from generated code.",
                    decl,
                )
                continue
            }
            if (decl.typeParameters.isNotEmpty()) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot auto-register generic subtype " +
                        "$fqn; register it explicitly with a concrete KSerializer.",
                    decl,
                )
                continue
            }
            val deferredBeforePolymorphic = deferred.size
            val hasPolymorphicSerializer = decl.hasAnnotation(POLYMORPHIC_FQN, decl, deferred)
            if (deferred.size != deferredBeforePolymorphic) continue
            if (hasPolymorphicSerializer) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot auto-register subtype $fqn because its class-level " +
                        "@Polymorphic serializer is not concrete; remove @Polymorphic from the subtype " +
                        "or register a concrete KSerializer explicitly.",
                    decl,
                )
                continue
            }
            val deferredBeforeSerializer = deferred.size
            val serializerFqn = serializableAnnotation.serializerFqn(decl, deferred) ?: continue
            if (deferred.size != deferredBeforeSerializer) continue
            if (serializerFqn != DEFAULT_SERIALIZER_FQN) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot verify custom serializer $serializerFqn " +
                        "for subtype $fqn; register it explicitly with a concrete KSerializer " +
                        "and verify it against the chosen wire format.",
                    decl,
                )
                continue
            }
            if (!decl.hasCompatibleSerializedPropertyNames(deferred)) continue
            for (base in bases) {
                registrations.getOrPut(base) { mutableListOf() }.add(decl)
            }
        }
        return registrations
    }

    private fun KSClassDeclaration.hasDiscoverableSubtypeKind(): Boolean =
        classKind == ClassKind.CLASS || classKind == ClassKind.OBJECT || classKind == ClassKind.ENUM_CLASS

    private fun KSClassDeclaration.hasGeneratedContributorKind(): Boolean =
        classKind == ClassKind.CLASS || classKind == ClassKind.OBJECT

    private fun KSClassDeclaration.polymorphicBases(
        deferred: MutableList<KSAnnotated>,
    ): Set<KotlinTypeRef> {
        val owner = this
        val found = linkedSetOf<KotlinTypeRef>()
        val visited = mutableSetOf<String>()

        fun visit(declaration: KSClassDeclaration) {
            for (superTypeReference in declaration.superTypes) {
                val superType = superTypeReference.resolve()
                if (superType.isError) {
                    deferred.addOnce(owner)
                    continue
                }
                val superDeclaration = superType.declaration.actualClassDeclaration() ?: continue
                val superFqn = superDeclaration.qualifiedName?.asString() ?: continue
                if (!visited.add(superFqn)) continue
                val deferredBeforeMarker = deferred.size
                val isPolymorphicBase = superDeclaration.hasAnnotation(POLYMORPHIC_BASE_FQN, owner, deferred)
                if (deferred.size != deferredBeforeMarker) continue
                if (isPolymorphicBase) {
                    val isValidBase = superDeclaration.hasCompatiblePolymorphicBaseShape(owner, deferred) &&
                        superDeclaration.hasCompatibleClassDiscriminator(owner, deferred)
                    if (isValidBase) {
                        superDeclaration.toTypeRef()?.let(found::add)
                    }
                }
                visit(superDeclaration)
            }
        }

        visit(this)
        return found
    }

    private fun KSAnnotated.findAnnotation(
        annotationFqn: String,
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): KSAnnotation? {
        var found: KSAnnotation? = null
        for (annotation in annotations) {
            val annotationType = annotation.annotationType.resolve()
            if (annotationType.isError) {
                deferred.addOnce(deferredOwner)
                continue
            }
            if (annotationType.declaration.actualClassDeclaration()?.qualifiedName?.asString() == annotationFqn) {
                found = annotation
            }
        }
        return found
    }

    private fun KSAnnotated.hasAnnotation(
        annotationFqn: String,
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Boolean = findAnnotation(annotationFqn, deferredOwner, deferred) != null

    private fun KSAnnotated.serialNameOrDefault(
        defaultName: String,
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? {
        val deferredBeforeAnnotation = deferred.size
        val annotation = findAnnotation(SERIAL_NAME_FQN, deferredOwner, deferred)
        if (deferred.size != deferredBeforeAnnotation) return null
        if (annotation == null) return defaultName
        return annotation.arguments
            .singleOrNull { it.name?.asString() == "value" }
            ?.value as? String ?: run {
            environment.logger.error(
                "SerializersModuleGenerator could not read kotlinx.serialization.SerialName.value.",
                deferredOwner,
            )
            null
        }
    }

    private fun KSClassDeclaration.hasCompatiblePolymorphicBaseShape(
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Boolean {
        val baseFqn = qualifiedName?.asString() ?: simpleName.asString()
        if (classKind != ClassKind.INTERFACE) {
            environment.logger.error(
                "SerializersModuleGenerator: @PolymorphicBase $baseFqn must be a non-sealed interface; " +
                    "found ${classKind.name.lowercase()}.",
                this,
            )
            return false
        }
        if (Modifier.SEALED in modifiers) {
            environment.logger.error(
                "SerializersModuleGenerator cannot auto-index sealed @PolymorphicBase $baseFqn; " +
                    "use its compiler-generated sealed serializer instead.",
                this,
            )
            return false
        }
        if (typeParameters.isNotEmpty()) {
            environment.logger.error(
                "SerializersModuleGenerator cannot auto-index generic @PolymorphicBase $baseFqn because " +
                    "polymorphic modules are keyed by erased KClass.",
                this,
            )
            return false
        }
        if (!isAccessibleFromGeneratedCode()) {
            environment.logger.error(
                "SerializersModuleGenerator cannot reference inaccessible @PolymorphicBase $baseFqn " +
                    "from generated code.",
                this,
            )
            return false
        }
        val deferredBeforeSerializable = deferred.size
        val serializable = findAnnotation(SERIALIZABLE_FQN, deferredOwner, deferred)
        if (deferred.size != deferredBeforeSerializable) return false
        if (serializable != null) {
            val serializerFqn = serializable.serializerFqn(deferredOwner, deferred) ?: return false
            if (serializerFqn != DEFAULT_SERIALIZER_FQN) {
                environment.logger.error(
                    "SerializersModuleGenerator cannot use custom serializer $serializerFqn on " +
                        "@PolymorphicBase $baseFqn because it bypasses the generated polymorphic module.",
                    this,
                )
                return false
            }
        }
        return true
    }

    private fun KSClassDeclaration.hasCompatibleClassDiscriminator(
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Boolean {
        val baseFqn = qualifiedName?.asString() ?: simpleName.asString()
        val visited = mutableSetOf<String>()

        fun visit(declaration: KSClassDeclaration): Boolean {
            val declarationFqn = declaration.qualifiedName?.asString() ?: return true
            if (!visited.add(declarationFqn)) return true
            val deferredBeforeAnnotation = deferred.size
            val annotation = declaration.findAnnotation(
                JSON_CLASS_DISCRIMINATOR_FQN,
                deferredOwner,
                deferred,
            )
            if (deferred.size != deferredBeforeAnnotation) return false
            if (annotation != null) {
                val discriminator = annotation.stringArgument("discriminator", deferredOwner) ?: return false
                if (discriminator != CLASS_DISCRIMINATOR) {
                    environment.logger.error(
                        "SerializersModuleGenerator cannot auto-register base $baseFqn because " +
                            "@JsonClassDiscriminator('$discriminator') conflicts with krig's " +
                            "'$CLASS_DISCRIMINATOR' wire discriminator.",
                        declaration,
                    )
                    return false
                }
            }
            for (superTypeReference in declaration.superTypes) {
                val superType = superTypeReference.resolve()
                if (superType.isError) {
                    deferred.addOnce(deferredOwner)
                    return false
                }
                val superDeclaration = superType.declaration.actualClassDeclaration() ?: continue
                if (!visit(superDeclaration)) return false
            }
            return true
        }

        return visit(this)
    }

    /**
     * Rejects descriptor names that collide with krig's object-polymorphism discriminator.
     * The serialization plugin inherits fields through the concrete superclass chain, including
     * private properties; KSP's visibility-filtered getAllProperties is therefore insufficient.
     */
    private fun KSClassDeclaration.hasCompatibleSerializedPropertyNames(
        deferred: MutableList<KSAnnotated>,
    ): Boolean {
        if (classKind == ClassKind.OBJECT) return true
        val subtypeFqn = qualifiedName?.asString() ?: simpleName.asString()
        val visitedClasses = mutableSetOf<String>()
        var currentClass: KSClassDeclaration? = this
        while (currentClass != null) {
            val currentClassFqn = currentClass.qualifiedName?.asString() ?: break
            if (!visitedClasses.add(currentClassFqn)) break
            for (property in currentClass.getDeclaredProperties()) {
                if (!property.isDelegated() && property.hasBackingField) {
                    val deferredBeforeTransient = deferred.size
                    val isTransient = property.hasAnnotation(TRANSIENT_FQN, this, deferred)
                    if (deferred.size != deferredBeforeTransient) return false
                    if (!isTransient) {
                        val propertyName = property.simpleName.asString()
                        val encodedName = property.serialNameOrDefault(propertyName, this, deferred) ?: return false
                        if (encodedName == CLASS_DISCRIMINATOR) {
                            environment.logger.error(
                                "SerializersModuleGenerator cannot auto-register subtype $subtypeFqn because " +
                                    "property $propertyName uses reserved class discriminator " +
                                    "'$CLASS_DISCRIMINATOR'; rename the property or its @SerialName.",
                                property,
                            )
                            return false
                        }
                        val deferredBeforeJsonNames = deferred.size
                        val jsonNames = property.findAnnotation(JSON_NAMES_FQN, this, deferred)
                        if (deferred.size != deferredBeforeJsonNames) return false
                        val aliases = if (jsonNames == null) {
                            emptyList()
                        } else {
                            jsonNames.stringListArgument("names", this) ?: return false
                        }
                        if (CLASS_DISCRIMINATOR in aliases) {
                            environment.logger.error(
                                "SerializersModuleGenerator cannot auto-register subtype $subtypeFqn because " +
                                    "property $propertyName uses reserved class discriminator " +
                                    "'$CLASS_DISCRIMINATOR' as a @JsonNames alias; rename the alias.",
                                property,
                            )
                            return false
                        }
                    }
                }
            }
            var superclass: KSClassDeclaration? = null
            for (superTypeReference in currentClass.superTypes) {
                val superType = superTypeReference.resolve()
                if (superType.isError) {
                    deferred.addOnce(this)
                    return false
                }
                val candidate = superType.declaration.actualClassDeclaration() ?: continue
                if (candidate.classKind != ClassKind.CLASS) continue
                if (candidate.qualifiedName?.asString() == "kotlin.Any") continue
                superclass = candidate
                break
            }
            currentClass = superclass?.takeIf {
                it.hasInternalGeneratedSerializer(this, deferred) ?: return false
            }
        }
        return true
    }

    /** Mirrors the stable public annotation surface behind the compiler's internal-serializer predicate. */
    private fun KSClassDeclaration.hasInternalGeneratedSerializer(
        deferredOwner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): Boolean? {
        val deferredBeforeSerializable = deferred.size
        val serializable = findAnnotation(SERIALIZABLE_FQN, deferredOwner, deferred)
        if (deferred.size != deferredBeforeSerializable) return null
        if (serializable != null) {
            val serializerFqn = serializable.serializerFqn(deferredOwner, deferred) ?: return null
            if (serializerFqn == DEFAULT_SERIALIZER_FQN) return true
            val deferredBeforeKeep = deferred.size
            val keepGenerated = hasAnnotation(KEEP_GENERATED_SERIALIZER_FQN, deferredOwner, deferred)
            if (deferred.size != deferredBeforeKeep) return null
            return keepGenerated
        }

        for (annotation in annotations) {
            val annotationType = annotation.annotationType.resolve()
            if (annotationType.isError) {
                deferred.addOnce(deferredOwner)
                return null
            }
            val annotationDeclaration = annotationType.declaration.actualClassDeclaration() ?: continue
            val deferredBeforeMeta = deferred.size
            val isMetaSerializable = annotationDeclaration.hasAnnotation(
                META_SERIALIZABLE_FQN,
                deferredOwner,
                deferred,
            )
            if (deferred.size != deferredBeforeMeta) return null
            if (isMetaSerializable) return true
        }
        return false
    }

    private fun KSAnnotation.stringArgument(name: String, owner: KSAnnotated): String? {
        val value = arguments.singleOrNull { it.name?.asString() == name }?.value as? String
        if (value == null) {
            environment.logger.error(
                "SerializersModuleGenerator could not read ${shortName.asString()}.$name.",
                owner,
            )
        }
        return value
    }

    private fun KSAnnotation.stringListArgument(name: String, owner: KSAnnotated): List<String>? {
        val values = arguments.singleOrNull { it.name?.asString() == name }?.value as? List<*>
        if (values == null) {
            environment.logger.error(
                "SerializersModuleGenerator could not read ${shortName.asString()}.$name.",
                owner,
            )
            return null
        }
        val strings = values.filterIsInstance<String>()
        if (strings.size != values.size) {
            environment.logger.error(
                "SerializersModuleGenerator could not read ${shortName.asString()}.$name.",
                owner,
            )
            return null
        }
        return strings
    }

    private fun KSAnnotation.serializerFqn(
        owner: KSAnnotated,
        deferred: MutableList<KSAnnotated>,
    ): String? {
        val serializerArguments = arguments.filter { it.name?.asString() == "with" }
        if (serializerArguments.isEmpty()) {
            // KSP cannot materialize some library annotation defaults for non-JVM metadata.
            return DEFAULT_SERIALIZER_FQN
        }
        val serializerType = serializerArguments.singleOrNull()?.value as? KSType
        if (serializerType == null) {
            environment.logger.error(
                "SerializersModuleGenerator could not read kotlinx.serialization.Serializable.with.",
                owner,
            )
            return null
        }
        if (serializerType.isError) {
            deferred.addOnce(owner)
            return null
        }
        return serializerType.declaration.actualClassDeclaration()?.qualifiedName?.asString() ?: run {
            environment.logger.error(
                "SerializersModuleGenerator could not resolve kotlinx.serialization.Serializable.with.",
                owner,
            )
            null
        }
    }

    /** Computes a [ContributorRef] for [cls]; null only when the class lacks a qualifiedName. */
    private fun computeContributorRef(
        base: KotlinTypeRef,
        cls: KSClassDeclaration,
        serialName: String,
        disambiguateBase: Boolean,
    ): ContributorRef? {
        val clsType = cls.toTypeRef() ?: return null
        val sourceFilePath = cls.containingFile?.filePath ?: return null
        val identity = if (disambiguateBase) "${base.fqn}::${clsType.fqn}" else clsType.fqn
        val readableName = clsType.simpleNames.last().generatedIdentifierStem(maxLength = 24)
        val contributorName = "Serializer_${readableName}_${stableGeneratedToken(identity)}_Contributor"
        return ContributorRef(
            valueName = contributorName,
            fileName = contributorName,
            base = base,
            subtype = clsType,
            serialName = serialName,
            sourceFilePath = sourceFilePath,
        )
    }

    /** Writes one `<SubclassName>_<hash>_Contributor.kt` with `aggregating = false`. */
    private fun emitContributor(
        outputPackage: String,
        cls: KSClassDeclaration,
        ref: ContributorRef,
    ) {
        val containingFile = cls.containingFile ?: run {
            environment.logger.warn(
                "SerializersModuleGenerator: '${ref.subtype.fqn}' has no containing file; skipping emission.",
            )
            return
        }

        val initializer = CodeBlock.builder()
            .add("%T {\n", SERIALIZERS_MODULE)
            .indent()
            .add("%M(%T::class) {\n", POLYMORPHIC, ref.base.className)
            .indent()
            .add("%M(%T::class)\n", SUBCLASS, ref.subtype.className)
            .unindent()
            .add("}\n")
            .unindent()
            .add("}")
            .build()
        val text = FileSpec.builder(outputPackage, ref.fileName)
            .addFileComment("Generated by krig-ksp-processor — do not edit by hand.")
            .addProperty(
                PropertySpec.builder(ref.valueName, SERIALIZERS_MODULE)
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc(
                        "Polymorphic registration of [%T] under [%T].\n",
                        ref.subtype.className,
                        ref.base.className,
                    )
                    .initializer(initializer)
                    .build(),
            )
            .build()
            .toString()

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
        val initializer = CodeBlock.builder().add("%T {\n", SERIALIZERS_MODULE).indent()
        for (ref in contributors.sortedBy { it.valueName }) initializer.add("include(%N)\n", ref.valueName)
        val text = FileSpec.builder(outputPackage, "GeneratedKrigSerializersModule")
            .addFileComment("Generated by krig-ksp-processor — do not edit by hand.")
            .addProperty(
                PropertySpec.builder("generatedKrigSerializersModule", SERIALIZERS_MODULE)
                    .addModifiers(KModifier.PUBLIC)
                    .addKdoc(
                        "Aggregated serializers of every `@PolymorphicBase` subtype discovered in this compilation.\n",
                    )
                    .initializer(initializer.unindent().add("}").build())
                    .build(),
            )
            .build()
            .toString()

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

    private fun KSClassDeclaration.toTypeRef(): KotlinTypeRef? {
        val fqn = qualifiedName?.asString() ?: return null
        val names = generateSequence(this) { current ->
            current.parentDeclaration as? KSClassDeclaration
        }.map { it.simpleName.asString() }.toList().asReversed()
        return KotlinTypeRef(fqn, packageName.asString(), names)
    }

    private data class KotlinTypeRef(
        val fqn: String,
        val packageName: String,
        val simpleNames: List<String>,
    ) {
        val className: ClassName = ClassName(
            packageName,
            simpleNames.first(),
            *simpleNames.drop(1).toTypedArray(),
        )
    }

    private data class ContributorRef(
        val valueName: String,
        val fileName: String,
        val base: KotlinTypeRef,
        val subtype: KotlinTypeRef,
        val serialName: String,
        val sourceFilePath: String,
    ) {
        val key: String get() = "${base.fqn}::${subtype.fqn}"
    }
}
