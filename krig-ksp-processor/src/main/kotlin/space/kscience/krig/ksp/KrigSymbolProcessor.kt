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
        listOf(
            FeatureSpecContractValidator(environment),
            ContributesAggregator(environment),
            SerializersModuleGenerator(environment),
        )
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
