package space.kscience.controls.validation

import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * A DataForge plugin that provides the factories for all built-in [FeatureValidator]s
 * that are part of the `controls-composite-old` module.
 *
 * Installing this plugin into a [Context] makes the standard validators discoverable
 * by the [FeatureValidatorRegistry].
 */
public class DefaultValidatorsPlugin(meta: Meta) : AbstractPlugin(meta) {
    override val tag: PluginTag get() = Companion.tag

    override fun content(target: String): Map<Name, Any> = when (target) {
        FeatureValidatorRegistry.VALIDATOR_FACTORY_TARGET -> mapOf(
            OperationalGuardsValidatorFactory.capability.asName() to OperationalGuardsValidatorFactory,
            RemoteMirrorValidatorFactory.capability.asName() to RemoteMirrorValidatorFactory
        )
        else -> super.content(target)
    }

    public companion object : PluginFactory<DefaultValidatorsPlugin> {
        override val tag: PluginTag = PluginTag("validation.default", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): DefaultValidatorsPlugin = DefaultValidatorsPlugin(meta)
    }
}