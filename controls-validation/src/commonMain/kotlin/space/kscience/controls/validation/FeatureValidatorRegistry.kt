package space.kscience.controls.validation

import space.kscience.controls.api.features.Feature
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * A DataForge plugin that acts as a service locator for [FeatureValidator] instances.
 * It discovers all available [FeatureValidatorFactory] plugins in the context and provides
 * a centralized way to retrieve the correct validator for a given [Feature].
 *
 * This registry is the core component of the extensible validation system.
 */
public class FeatureValidatorRegistry(meta: Meta) : AbstractPlugin(meta) {
    override val tag: PluginTag get() = Companion.tag

    private val factories by lazy {
        context.gather<FeatureValidatorFactory>(VALIDATOR_FACTORY_TARGET)
    }

    /**
     * Finds the appropriate [FeatureValidator] for the given [feature] by matching its `capability` string.
     *
     * @param feature The feature for which a validator is needed.
     * @return A [FeatureValidator] instance if a matching factory is found, otherwise `null`.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <F : Feature> getValidatorFor(feature: F): FeatureValidator<F>? {
        TODO()
    }

    public companion object : PluginFactory<FeatureValidatorRegistry> {
        override val tag: PluginTag = PluginTag("validation.registry", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The target name used by the DataForge plugin system to discover [FeatureValidatorFactory] implementations.
         */
        public const val VALIDATOR_FACTORY_TARGET: String = "controls.validation.factory"

        override fun build(context: Context, meta: Meta): FeatureValidatorRegistry = FeatureValidatorRegistry(meta)
    }
}

/**
 * A convenience extension to get the [FeatureValidatorRegistry] from a context.
 * Throws an error if the plugin is not installed.
 */
public val Context.featureValidatorRegistry: FeatureValidatorRegistry
    get() = plugins.find(true) { it is FeatureValidatorRegistry } as? FeatureValidatorRegistry
        ?: error("FeatureValidatorRegistry plugin is not installed in the context.")