package space.kscience.krig.core.features

import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.dataforge.names.Name
import kotlin.reflect.KClass

/**
 * Manifest-to-runtime adapter for operation pipeline assembly.
 *
 * [F] is the serializable Manifest DTO; [C] is runtime configuration used to
 * configure a [PipelineBuilder].
 */
public interface PipelineFeature<C : Any, F : PipelineFeatureSpec> {
    /** Stable identifier matching the DTO's `@SerialName`. */
    public val id: Name

    /** DTO class; used by [installFromSpec] to reject a wrong-typed [PipelineFeatureSpec]. */
    public val specClass: KClass<F>

    public fun createConfig(): C

    /** Reads runtime config from a Manifest DTO. Default ignores the DTO. */
    public fun configureFromSpec(spec: F): C = createConfig()

    /** Translates [config] into operation policies and optional local capabilities. */
    public fun install(config: C, pipeline: PipelineBuilder)

    /** Type-erased entry used by `materializePipeline`. Throws on type mismatch. */
    public fun installFromSpec(spec: PipelineFeatureSpec, pipeline: PipelineBuilder) {
        val expected = specClass
        if (expected != PipelineFeatureSpec::class && !expected.isInstance(spec)) {
            throw PipelineFeatureSpecMismatchException(
                "PipelineFeature '$id' expected a DTO of type ${expected.simpleName} but got ${spec::class.simpleName}.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        install(configureFromSpec(spec as F), pipeline)
    }
}

internal class PipelineFeatureSpecMismatchException(message: String) : IllegalArgumentException(message)
