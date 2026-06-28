package space.kscience.krig.ksp

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
        const val GENERATED_PACKAGE_ROOT = "space.kscience.krig.generated"
    }

    private val emittedContracts: MutableSet<String> = mutableSetOf()
    private val generatedObjectNames: MutableMap<String, String> = linkedMapOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val moduleSuffix = environment.options["krig.generated.module"]
            ?: error(
                "krig-ksp-processor requires the 'krig.generated.module' KSP argument. " +
                    "Apply the `krig-mpp-ksp` convention plugin which sets it automatically.",
            )
        val generatedPackage = "$GENERATED_PACKAGE_ROOT.$moduleSuffix"

        val deferred = mutableListOf<KSAnnotated>()
        val contractDeclarations = resolver.getSymbolsWithAnnotation(KRIG_DEVICE_CONTRACT_FQN)
            .filterIsInstance<KSClassDeclaration>()

        for (decl in contractDeclarations) {
            if (!decl.validate()) {
                deferred += decl
                continue
            }
            val fqn = decl.qualifiedName?.asString() ?: continue
            if (fqn in emittedContracts) continue

            val contract = readContractAnnotation(decl, deferred) ?: continue
            if (deferred.contains(decl)) continue
            if (!validateContractShape(resolver, decl, deferred)) continue
            if (deferred.contains(decl)) continue

            val generatedObjectName = "${decl.simpleName.asString()}Generated"
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
            val constructor = decl.primaryConstructor
            if (constructor != null && constructor.parameters.isNotEmpty()) {
                environment.logger.error(
                    "@KrigDeviceContract class '$fqn' must have a public no-arg constructor.",
                    constructor,
                )
                return false
            }
            if (constructor != null && Modifier.PRIVATE in constructor.modifiers) {
                environment.logger.error(
                    "@KrigDeviceContract class '$fqn' must have a public no-arg constructor.",
                    constructor,
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
        val contractExpression = when (contractDeclaration.classKind) {
            ClassKind.OBJECT -> fqn
            ClassKind.CLASS -> "$fqn()"
            else -> return
        }
        val text = generatedKotlinFile(
            outputPackage = outputPackage,
            imports = listOf(
                "kotlinx.serialization.json.JsonObject",
                "space.kscience.dataforge.names.parseAsName",
                "space.kscience.krig.core.contracts.DeviceManifest",
                "space.kscience.krig.core.contracts.toJsonSchema",
                "space.kscience.krig.core.meta.DeviceContractRegistry",
                "space.kscience.krig.core.meta.deviceContractRegistry",
            ),
        ) {
            appendLine("/** Generated common artifacts for [$fqn]. */")
            appendLine("public object $generatedObjectName {")
            appendLine("    public val registry: DeviceContractRegistry = deviceContractRegistry(")
            appendLine("        id = ${contract.id.quoted()}.parseAsName(),")
            appendLine("        contract = $contractExpression,")
            appendLine("        version = ${contract.version.quoted()},")
            appendLine("        deviceContractFqName = ${fqn.quoted()},")
            appendLine("    )")
            appendLine()
            appendLine("    public fun manifest(): DeviceManifest = registry.manifest")
            appendLine()
            appendLine("    public val schemaHash: String get() = registry.schemaHash")
            appendLine()
            appendLine("    public fun jsonSchema(): JsonObject = registry.manifest.toJsonSchema()")
            appendLine("}")
        }

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

private fun String.quoted(): String = buildString {
    append('"')
    for (char in this@quoted) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}
