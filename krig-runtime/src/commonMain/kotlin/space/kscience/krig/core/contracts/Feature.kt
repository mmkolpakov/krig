package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.dataforge.names.Name
import kotlin.reflect.KClass

/**
 * Installable behavior unit. [F] is the serializable blueprint DTO; [C] is
 * runtime configuration used to mutate a [PipelineBuilder].
 */
public interface Feature<C : Any, F : FeatureSpec> {
    /** Stable identifier matching the DTO's `@SerialName`. */
    public val id: Name

    /** DTO class; used by [installFromSpec] to reject a wrong-typed [FeatureSpec]. */
    public val specClass: KClass<F>

    public fun createConfig(): C

    /** Reads runtime config from a blueprint DTO. Default ignores the DTO. */
    public fun configureFromSpec(spec: F): C = createConfig()

    /** Translates [config] into gates / observers / capabilities in [pipeline]. */
    public fun install(config: C, pipeline: PipelineBuilder)

    /** Type-erased entry used by `materializePipeline`. Throws on type mismatch. */
    public fun installFromSpec(spec: FeatureSpec, pipeline: PipelineBuilder) {
        val expected = specClass
        if (expected != FeatureSpec::class && !expected.isInstance(spec)) {
            throw FeatureSpecMismatchException(
                "Feature '$id' expected a DTO of type ${expected.simpleName} but got ${spec::class.simpleName}.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        install(configureFromSpec(spec as F), pipeline)
    }
}

internal class FeatureSpecMismatchException(message: String) : IllegalArgumentException(message)
