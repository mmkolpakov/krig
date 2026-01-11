package space.kscience.controls.automation

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.controls.core.capabilities.CapabilityFactory
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A plugin providing Automation features.
 */
public class AutomationFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = automationSerializersModule

    override fun content(target: String): Map<Name, Any> = when (target) {
        CapabilityFactory.TARGET -> mapOf(
            PlanExecutorSpec.name to CapabilityFactory<PlanExecutorFeature, PlanExecutorCapability> { _, _, _ ->
                TODO("Implement PlanExecutorCapabilityImpl")
            },
            TaskExecutorSpec.name to CapabilityFactory<TaskExecutorFeature, TaskExecutorCapability> { _, _, _ ->
                TODO("Implement TaskExecutorCapabilityImpl")
            }
        )
        else -> emptyMap()
    }

    public companion object : PluginFactory<AutomationFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.automation", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): AutomationFeaturePlugin = AutomationFeaturePlugin()
    }
}