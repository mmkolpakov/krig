package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.core.pipeline.TypedPipelineBuilder
import kotlin.reflect.KClass

/**
 * Composable unit of device configuration. [F] is the serializable blueprint DTO,
 * [C] the mutable runtime config. [id] and [featureClass] identify the DTO handled by
 * this installer. DTO serial-name invariants are enforced by the KSP DeviceFeatureSpec validator.
 */
public interface DeviceFeatureInstaller<C : Any, F : DeviceFeatureSpec> {
    /** Stable identifier matching the DTO's `@SerialName` (e.g. `"feature.caching"`). */
    public val id: String

    /** DTO class; used by [installFromFeature] to reject a wrong-typed [DeviceFeatureSpec]. */
    public val featureClass: KClass<F>

    public fun createConfig(): C

    /** Reads runtime config from a blueprint DTO. Default ignores the DTO. */
    public fun configureFromFeature(feature: F): C = createConfig()

    /** Translates [config] into gates / observers / capabilities in [pipeline]. */
    public fun install(config: C, pipeline: TypedPipelineBuilder)

    /** Type-erased entry used by `materializeTypedPipeline`. Throws on type mismatch. */
    public fun installFromFeature(feature: DeviceFeatureSpec, pipeline: TypedPipelineBuilder) {
        val fc = featureClass
        require(fc.isInstance(feature)) {
            "DeviceFeatureInstaller '$id' expected a DTO of type ${fc.simpleName} " +
                    "but got ${feature::class.simpleName}. " +
                    "This usually means either the runtime 'id' disagrees with the DTO '@SerialName' " +
                    "or the wrong installer was registered for this id."
        }
        @Suppress("UNCHECKED_CAST")
        install(configureFromFeature(feature as F), pipeline)
    }
}
