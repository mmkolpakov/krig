package space.kscience.krig.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec

/**
 * Common KSP generator for stable device-contract artifacts.
 *
 * The generator intentionally emits common Kotlin only: typed registry, manifest factory,
 * schema hash and JSON Schema projection. JVM auto-contribution remains a separate
 * assembly/JVM aggregation concern.
 */
internal class DeviceContractGenerator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    internal companion object {
        const val KRIG_DEVICE_CONTRACT_FQN = "space.kscience.krig.api.annotations.KrigDeviceContract"
        const val DEVICE_CONTRACT_BUILDER_FQN = "space.kscience.krig.core.meta.DeviceContractBuilder"
        const val GENERATED_FORM_SCHEMA_OPTION = "krig.generated.formSchema"

        private val JSON_OBJECT = ClassName("kotlinx.serialization.json", "JsonObject")
        private val DEVICE_MANIFEST = ClassName("space.kscience.krig.core.contracts", "DeviceManifest")
        private val DEVICE_CONTRACT_REGISTRY =
            ClassName("space.kscience.krig.core.meta", "DeviceContractRegistry")
        private val DEVICE_FORM_SCHEMA = ClassName("space.kscience.krig.ui.schema", "DeviceFormSchema")
        private val PARSE_AS_NAME = MemberName("space.kscience.dataforge.names", "parseAsName")
        private val DEVICE_CONTRACT_REGISTRY_FACTORY =
            MemberName("space.kscience.krig.core.meta", "deviceContractRegistry")
        private val TO_JSON_SCHEMA = MemberName("space.kscience.krig.core.contracts", "toJsonSchema")
        private val TO_DEVICE_FORM_SCHEMA =
            MemberName("space.kscience.krig.ui.schema", "toDeviceFormSchema")

        internal fun generatedArtifactName(fqn: String, simpleName: String): String =
            "DeviceContract_${simpleName.generatedIdentifierStem(maxLength = 24)}_" +
                "${stableGeneratedToken(fqn)}_Generated"
    }

    private val emittedContracts: MutableSet<String> = mutableSetOf()
    private val generatedObjectNames: MutableMap<String, String> = linkedMapOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val generatedPackage = environment.requireGeneratedNamespace().packageName

        val deferred = mutableListOf<KSAnnotated>()
        val contractDeclarations = resolver.getSymbolsWithAnnotation(KRIG_DEVICE_CONTRACT_FQN)
            .filterIsInstance<KSClassDeclaration>()

        for (decl in contractDeclarations) {
            if (!decl.validate()) {
                deferred += decl
                continue
            }
            val fqn = decl.qualifiedName?.asString()
            if (fqn == null) {
                environment.logger.error(
                    "@KrigDeviceContract is supported only on declarations with a stable qualified name.",
                    decl,
                )
                continue
            }
            if (fqn in emittedContracts) continue

            val contract = readContractAnnotation(decl, deferred) ?: continue
            if (deferred.contains(decl)) continue
            if (!validateContractShape(resolver, decl, deferred)) continue
            if (deferred.contains(decl)) continue

            val generatedObjectName = generatedArtifactName(fqn, decl.simpleName.asString())
            val previousFqn = generatedObjectNames.putIfAbsent(generatedObjectName, fqn)
            if (previousFqn != null && previousFqn != fqn) {
                environment.logger.error(
                    "Generated contract object name '$generatedObjectName' is ambiguous for " +
                        "$previousFqn and $fqn. Rename one contract before generation.",
                    decl,
                )
                continue
            }

            emitContractArtifact(
                outputPackage = generatedPackage,
                generatedObjectName = generatedObjectName,
                contractDeclaration = decl,
                contract = contract,
            )
            emittedContracts += fqn
        }

        return deferred
    }

    private fun readContractAnnotation(
        decl: KSClassDeclaration,
        deferred: MutableList<KSAnnotated>,
    ): ContractAnnotation? {
        val annotation = decl.annotations.firstOrNull { ann ->
            ann.annotationType.safeResolve(decl, deferred)
                ?.declaration
                ?.qualifiedName
                ?.asString() == KRIG_DEVICE_CONTRACT_FQN
        } ?: return null
        if (deferred.contains(decl)) return null

        val id = annotation.stringArg("id")
        val version = annotation.stringArg("version") ?: "0.1.0"
        if (id.isNullOrBlank()) {
            environment.logger.error(
                "@KrigDeviceContract on ${decl.qualifiedName?.asString()} requires a non-blank id.",
                decl,
            )
            return null
        }
        if (version.isBlank()) {
            environment.logger.error(
                "@KrigDeviceContract on ${decl.qualifiedName?.asString()} requires a non-blank version.",
                decl,
            )
            return null
        }
        return ContractAnnotation(id = id, version = version)
    }

    private fun validateContractShape(
        resolver: Resolver,
        decl: KSClassDeclaration,
        deferred: MutableList<KSAnnotated>,
    ): Boolean {
        val fqn = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
        if (decl.classKind != ClassKind.OBJECT && decl.classKind != ClassKind.CLASS) {
            environment.logger.error(
                "@KrigDeviceContract supports only object declarations or public no-arg classes; '$fqn' is " +
                    decl.classKind.name.lowercase() + ".",
                decl,
            )
            return false
        }
        if (Modifier.PRIVATE in decl.modifiers || Modifier.INTERNAL in decl.modifiers) {
            environment.logger.error(
                "@KrigDeviceContract declaration '$fqn' must be public so generated common API can reference it.",
                decl,
            )
            return false
        }
        if (!decl.isAccessibleFromGeneratedPackage()) {
            environment.logger.error(
                "@KrigDeviceContract declaration '$fqn' and all its enclosing declarations must be public.",
                decl,
            )
            return false
        }
        if (Modifier.SEALED in decl.modifiers) {
            environment.logger.error(
                "@KrigDeviceContract declaration '$fqn' must be concrete; sealed classes are not supported.",
                decl,
            )
            return false
        }
        if (Modifier.ABSTRACT in decl.modifiers) {
            environment.logger.error(
                "@KrigDeviceContract declaration '$fqn' must be concrete; abstract classes are not supported.",
                decl,
            )
            return false
        }
        if (Modifier.INNER in decl.modifiers) {
            environment.logger.error(
                "@KrigDeviceContract class '$fqn' must not be inner because generated code has no enclosing instance.",
                decl,
            )
            return false
        }
        if (decl.typeParameters.isNotEmpty()) {
            environment.logger.error(
                "@KrigDeviceContract class '$fqn' must not declare type parameters.",
                decl,
            )
            return false
        }

        val expectedDeclaration = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(DEVICE_CONTRACT_BUILDER_FQN),
        ) ?: run {
            environment.logger.error(
                "KRig KSP processor could not resolve expected type $DEVICE_CONTRACT_BUILDER_FQN while " +
                    "validating @KrigDeviceContract. Check processor/runtime classpath.",
                decl,
            )
            return false
        }
        val expectedType = expectedDeclaration.asStarProjectedType()
        val actualType = decl.asStarProjectedType()
        if (actualType.isError) {
            deferred += decl
            return false
        }
        if (!expectedType.isAssignableFrom(actualType)) {
            environment.logger.error(
                "$fqn is annotated with @KrigDeviceContract but does not extend $DEVICE_CONTRACT_BUILDER_FQN.",
                decl,
            )
            return false
        }

        if (decl.classKind == ClassKind.CLASS) {
            val constructors = decl.getConstructors().toList()
            val noArgConstructors = constructors.filter { it.parameters.isEmpty() }
            if (noArgConstructors.isEmpty()) {
                environment.logger.error(
                    "@KrigDeviceContract class '$fqn' must have a public no-arg constructor.",
                    decl,
                )
                return false
            }
            val callableConstructor = noArgConstructors.firstOrNull { constructor ->
                constructor.modifiers.none { it in INACCESSIBLE_VISIBILITY_MODIFIERS }
            }
            if (callableConstructor == null) {
                environment.logger.error(
                    "@KrigDeviceContract class '$fqn' must have a public no-arg constructor; " +
                        "private, protected and internal constructors cannot be called from generated code.",
                    noArgConstructors.first(),
                )
                return false
            }
        }
        return true
    }

    private fun emitContractArtifact(
        outputPackage: String,
        generatedObjectName: String,
        contractDeclaration: KSClassDeclaration,
        contract: ContractAnnotation,
    ) {
        val fqn = contractDeclaration.qualifiedName?.asString() ?: return
        val containingFile = contractDeclaration.containingFile ?: run {
            environment.logger.warn("DeviceContractGenerator: '$fqn' has no containing file; skipping emission.")
            return
        }
        val contractClassName = contractDeclaration.toClassName()
        val contractExpression = when (contractDeclaration.classKind) {
            ClassKind.OBJECT -> CodeBlock.of("%T", contractClassName)
            ClassKind.CLASS -> CodeBlock.of("%T()", contractClassName)
            else -> return
        }
        val includeFormSchema = environment.booleanOption(GENERATED_FORM_SCHEMA_OPTION)
        val registryInitializer = CodeBlock.builder()
            .add("%M(\n", DEVICE_CONTRACT_REGISTRY_FACTORY)
            .indent()
            .add("id = %S.%M(),\n", contract.id, PARSE_AS_NAME)
            .add("contract = %L,\n", contractExpression)
            .add("version = %S,\n", contract.version)
            .add("deviceContractFqName = %S,\n", fqn)
            .unindent()
            .add(")")
            .build()
        val generatedObject = TypeSpec.objectBuilder(generatedObjectName)
            .addModifiers(KModifier.PUBLIC)
            .addKdoc("Generated common artifacts for [%T].\n", contractClassName)
            .addProperty(
                PropertySpec.builder("registry", DEVICE_CONTRACT_REGISTRY)
                    .addModifiers(KModifier.PUBLIC)
                    .initializer(registryInitializer)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("manifest")
                    .addModifiers(KModifier.PUBLIC)
                    .returns(DEVICE_MANIFEST)
                    .addStatement("return registry.manifest")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("schemaHash", STRING)
                    .addModifiers(KModifier.PUBLIC)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return registry.schemaHash")
                            .build(),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("jsonSchema")
                    .addModifiers(KModifier.PUBLIC)
                    .returns(JSON_OBJECT)
                    .addStatement("return registry.manifest.%M()", TO_JSON_SCHEMA)
                    .build(),
            )
            .apply {
                if (includeFormSchema) {
                    addFunction(
                        FunSpec.builder("formSchema")
                            .addModifiers(KModifier.PUBLIC)
                            .returns(DEVICE_FORM_SCHEMA)
                            .addStatement("return registry.manifest.%M()", TO_DEVICE_FORM_SCHEMA)
                            .build(),
                    )
                }
            }
            .build()
        val text = FileSpec.builder(outputPackage, generatedObjectName)
            .addFileComment("Generated by krig-ksp-processor — do not edit by hand.")
            .addType(generatedObject)
            .build()
            .toString()

        val file = environment.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, containingFile),
            packageName = outputPackage,
            fileName = generatedObjectName,
        )
        file.write(text.toByteArray())
        file.close()
    }

    private data class ContractAnnotation(val id: String, val version: String)
}

private fun KSAnnotation.stringArg(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

private fun SymbolProcessorEnvironment.booleanOption(name: String): Boolean =
    when (val value = options[name]?.trim()?.lowercase()) {
        null, "", "false" -> false
        "true" -> true
        else -> error("Unsupported '$name' value '$value'. Expected true or false.")
    }

private fun KSTypeReference.safeResolve(
    owner: KSAnnotated,
    deferred: MutableList<KSAnnotated>,
): KSType? {
    val type = resolve()
    if (type.isError) {
        deferred += owner
        return null
    }
    return type
}

private fun KSClassDeclaration.isAccessibleFromGeneratedPackage(): Boolean =
    generateSequence(this) { declaration ->
        declaration.parentDeclaration as? KSClassDeclaration
    }.all { declaration -> declaration.modifiers.none { it in INACCESSIBLE_VISIBILITY_MODIFIERS } }

private fun KSClassDeclaration.toClassName(): ClassName {
    val simpleNames = generateSequence(this) { declaration ->
        declaration.parentDeclaration as? KSClassDeclaration
    }.map { it.simpleName.asString() }.toList().asReversed()
    return ClassName(packageName.asString(), simpleNames.first(), *simpleNames.drop(1).toTypedArray())
}

private val INACCESSIBLE_VISIBILITY_MODIFIERS: Set<Modifier> =
    setOf(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL)
