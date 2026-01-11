package space.kscience.controls.fsm

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.controls.core.capabilities.CapabilityFactory
import space.kscience.controls.fsm.capability.LifecycleCapability
import space.kscience.controls.fsm.capability.OperationalFsmCapability
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A plugin providing Finite State Machine capabilities (Lifecycle, Operational FSM) to the runtime.
 */
public class FsmFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = fsmSerializersModule

    override fun content(target: String): Map<Name, Any> = when(target) {
        CapabilityFactory.TARGET -> mapOf(
            LifecycleSpec.name to CapabilityFactory<LifecycleFeature, LifecycleCapability> { _, _, _ ->
                TODO("Implement LifecycleCapabilityImpl")
            },
            OperationalFsmSpec.name to CapabilityFactory<OperationalFsmFeature, OperationalFsmCapability> { _, _, _ ->
                TODO("Implement OperationalFsmCapabilityImpl")
            },
            OperationalGuardsSpec.name to CapabilityFactory<OperationalGuardsFeature, GuardCapability> { _, _, _ ->
                TODO("Implement GuardCapabilityImpl")
            },
            IntrospectionSpec.name to CapabilityFactory<IntrospectionFeature, IntrospectionCapability> { _, _, _ ->
                TODO("Implement IntrospectionCapabilityImpl")
            }
        )
        else -> emptyMap()
    }

    public companion object : PluginFactory<FsmFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.fsm", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): FsmFeaturePlugin = FsmFeaturePlugin()
    }
}