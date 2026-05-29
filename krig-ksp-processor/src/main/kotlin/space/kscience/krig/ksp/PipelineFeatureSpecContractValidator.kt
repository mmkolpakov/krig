package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/** Compile-time validator for the stable identity contract of PipelineFeatureSpec DTOs. */
internal class PipelineFeatureSpecContractValidator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    private companion object {
        const val KRIG_FEATURE_FQN = "space.kscience.krig.api.annotations.KrigPipelineFeatureSpec"
        const val SERIAL_NAME_FQN = "kotlinx.serialization.SerialName"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val features = resolver.getSymbolsWithAnnotation(KRIG_FEATURE_FQN)
            .filterIsInstance<KSClassDeclaration>()

        for (pipelineFeature in features) {
            if (!pipelineFeature.validate()) {
                deferred += pipelineFeature
                continue
            }
            validate(pipelineFeature)
        }

        return deferred
    }

    private fun validate(pipelineFeature: KSClassDeclaration) {
        val fqn = pipelineFeature.qualifiedName?.asString() ?: "unknown"

        // (1) extract @KrigPipelineFeatureSpec(id = ?)
        val krigFeatureAnnotation = pipelineFeature.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == KRIG_FEATURE_FQN
        }
        val featureId = krigFeatureAnnotation
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "id" }
            ?.value as? String

        if (featureId == null) {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: missing required `id` argument.",
                pipelineFeature,
            )
            return
        }

        // (2) extract @SerialName("?")
        val serialName = pipelineFeature.annotations
            .firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == SERIAL_NAME_FQN
            }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "value" }
            ?.value as? String

        if (serialName == null) {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: must also be annotated with @SerialName(\"$featureId\") " +
                    "so the PipelineFeatureSpec can round-trip through kotlinx.serialization.",
                pipelineFeature,
            )
        } else if (serialName != featureId) {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: id=\"$featureId\" must equal @SerialName=\"$serialName\". " +
                    "These two are the same identity at runtime and must agree at compile time.",
                pipelineFeature,
            )
        }

        // (3) companion object with const val ID: String = featureId
        val companion = pipelineFeature.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }

        if (companion == null) {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: must declare a `companion object { const val ID: String = \"$featureId\" }`. " +
                    "Runtime PipelineFeature implementations reference this constant.",
                pipelineFeature,
            )
            return
        }

        val idDeclaration = companion.declarations
            .filterIsInstance<KSPropertyDeclaration>()
            .firstOrNull { it.simpleName.asString() == "ID" }

        if (idDeclaration == null) {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: companion object must expose `const val ID: String = \"$featureId\"`.",
                companion,
            )
            return
        }

        val idType = idDeclaration.type.resolve().declaration.qualifiedName?.asString()
        if (idDeclaration.isMutable || Modifier.CONST !in idDeclaration.modifiers || idType != "kotlin.String") {
            environment.logger.error(
                "@KrigPipelineFeatureSpec on $fqn: companion ID must be declared exactly as " +
                    "`const val ID: String = \"$featureId\"`.",
                idDeclaration,
            )
        }
        environment.logger.info(
            "PipelineFeatureSpecContractValidator: $fqn passes identity-contract check (id=\"$featureId\").",
            pipelineFeature,
        )
    }
}
