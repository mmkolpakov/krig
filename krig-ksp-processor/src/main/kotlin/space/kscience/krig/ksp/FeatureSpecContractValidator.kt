package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/** Compile-time validator for the stable identity contract of FeatureSpec DTOs. */
internal class FeatureSpecContractValidator(
    private val environment: SymbolProcessorEnvironment,
) : Generator {

    private companion object {
        const val KRIG_FEATURE_FQN = "space.kscience.krig.api.annotations.KrigFeatureSpec"
        const val SERIAL_NAME_FQN = "kotlinx.serialization.SerialName"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val features = resolver.getSymbolsWithAnnotation(KRIG_FEATURE_FQN)
            .filterIsInstance<KSClassDeclaration>()

        for (feature in features) {
            if (!feature.validate()) {
                deferred += feature
                continue
            }
            validate(feature)
        }

        return deferred
    }

    private fun validate(feature: KSClassDeclaration) {
        val fqn = feature.qualifiedName?.asString() ?: "unknown"

        // (1) extract @KrigFeatureSpec(id = ?)
        val krigFeatureAnnotation = feature.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == KRIG_FEATURE_FQN
        }
        val featureId = krigFeatureAnnotation
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "id" }
            ?.value as? String

        if (featureId == null) {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: missing required `id` argument.",
                feature,
            )
            return
        }

        // (2) extract @SerialName("?")
        val serialName = feature.annotations
            .firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == SERIAL_NAME_FQN
            }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "value" }
            ?.value as? String

        if (serialName == null) {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: must also be annotated with @SerialName(\"$featureId\") " +
                    "so the FeatureSpec can round-trip through kotlinx.serialization.",
                feature,
            )
        } else if (serialName != featureId) {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: id=\"$featureId\" must equal @SerialName=\"$serialName\". " +
                    "These two are the same identity at runtime — they must agree at compile time.",
                feature,
            )
        }

        // (3) companion object with const val ID: String = featureId
        val companion = feature.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }

        if (companion == null) {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: must declare a `companion object { const val ID: String = \"$featureId\" }`. " +
                    "Runtime feature implementations reference this constant.",
                feature,
            )
            return
        }

        val idDeclaration = companion.declarations
            .filterIsInstance<KSPropertyDeclaration>()
            .firstOrNull { it.simpleName.asString() == "ID" }

        if (idDeclaration == null) {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: companion object must expose `const val ID: String = \"$featureId\"`.",
                companion,
            )
            return
        }

        val idType = idDeclaration.type.resolve().declaration.qualifiedName?.asString()
        if (idDeclaration.isMutable || Modifier.CONST !in idDeclaration.modifiers || idType != "kotlin.String") {
            environment.logger.error(
                "@KrigFeatureSpec on $fqn: companion ID must be declared exactly as " +
                    "`const val ID: String = \"$featureId\"`.",
                idDeclaration,
            )
        }
        environment.logger.info(
            "FeatureSpecContractValidator: $fqn passes identity-contract check (id=\"$featureId\").",
            feature,
        )
    }
}
