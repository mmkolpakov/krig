package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Main KSP processor for krig. Delegates to a list of independent generators,
 * each owning one extension point. Order is irrelevant — each generator processes its
 * own annotation set without sharing state with the others.
 */
public class KrigSymbolProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {

    private val generators: List<Generator> by lazy {
        when (environment.processingLayer()) {
            KrigProcessingLayer.COMMON -> commonGenerators(environment)
            KrigProcessingLayer.JVM_AGGREGATION -> jvmAggregationGenerators(environment)
            KrigProcessingLayer.ALL -> commonGenerators(environment) + jvmAggregationGenerators(environment)
        }
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        for (generator in generators) {
            deferred += generator.process(resolver)
        }
        return deferred
    }
}

/**
 * Base interface for individual code generators within the krig KSP processor.
 */
internal interface Generator {
    fun process(resolver: Resolver): List<KSAnnotated>
}

internal enum class KrigProcessingLayer {
    COMMON,
    JVM_AGGREGATION,
    ALL,
}

internal const val KRIG_GENERATED_LAYER_OPTION: String = "krig.generated.layer"

private fun SymbolProcessorEnvironment.processingLayer(): KrigProcessingLayer {
    val platformNames = platforms.map { it.platformName }
    return resolveProcessingLayer(options[KRIG_GENERATED_LAYER_OPTION], platformNames).also { layer ->
        logger.info(
            "KRig KSP processing layer: $layer " +
                "(option=${options[KRIG_GENERATED_LAYER_OPTION].orEmpty()}, platforms=$platformNames)",
        )
    }
}

internal fun resolveProcessingLayer(
    configuredValue: String?,
    platformNames: List<String>,
): KrigProcessingLayer {
    return when (val configured = configuredValue?.trim()?.lowercase()) {
        null, "", "all" -> KrigProcessingLayer.ALL
        "common", "metadata", "commonmainmetadata" -> KrigProcessingLayer.COMMON
        "jvm", "jvmaggregation", "jvm-aggregation" -> KrigProcessingLayer.JVM_AGGREGATION
        "auto" -> inferProcessingLayer(platformNames)
        else -> error(
            "Unsupported '$KRIG_GENERATED_LAYER_OPTION' value '$configured'. " +
                "Expected one of: auto, common, jvmAggregation, all.",
        )
    }
}

private fun inferProcessingLayer(platformNames: List<String>): KrigProcessingLayer {
    val normalized = platformNames.map { it.lowercase() }
    return when {
        normalized.any { it.contains("common") || it.contains("metadata") } -> KrigProcessingLayer.COMMON
        normalized.size > 1 -> KrigProcessingLayer.COMMON
        normalized.any { it.contains("jvm") } -> KrigProcessingLayer.JVM_AGGREGATION
        normalized.isNotEmpty() -> KrigProcessingLayer.COMMON
        else -> KrigProcessingLayer.ALL
    }
}

private fun commonGenerators(environment: SymbolProcessorEnvironment): List<Generator> =
    listOf(
        PipelineFeatureSpecContractValidator(environment),
        SerializersModuleGenerator(environment),
        DeviceContractGenerator(environment),
    )

private fun jvmAggregationGenerators(environment: SymbolProcessorEnvironment): List<Generator> =
    listOf(
        ContributesAggregator(environment),
    )
