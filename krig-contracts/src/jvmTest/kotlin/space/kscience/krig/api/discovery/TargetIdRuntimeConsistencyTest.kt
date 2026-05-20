package space.kscience.krig.api.discovery

import space.kscience.krig.assembly.BlueprintPlugin
import space.kscience.krig.assembly.DeviceFactoryPlugin
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the invariant that every `@TargetId("...")`-annotated anchor's runtime
 * `Target: ContributionTarget<*>` has `id == @TargetId.value`. Catches drift between the
 * literal KSP reads and the literal the runtime registers under.
 */
class TargetIdRuntimeConsistencyTest {

    private fun assertAnchor(anchor: KClass<*>, runtimeId: String) {
        val annotation = anchor.findAnnotation<TargetId>()
            ?: error("${anchor.simpleName} must carry @TargetId")
        assertEquals(
            annotation.value,
            runtimeId,
            "@TargetId on ${anchor.simpleName} is \"${annotation.value}\" but runtime id is \"$runtimeId\"",
        )
    }

    @Test
    fun featureContributions() =
        assertAnchor(FeatureContributions::class, FeatureContributions.Target.id)

    @Test
    fun protocolContributions() =
        assertAnchor(ProtocolContributions::class, ProtocolContributions.Target.id)

    @Test
    fun actionHandlerContributions() =
        assertAnchor(ActionHandlerContributions::class, ActionHandlerContributions.Target.id)

    @Test
    fun blueprintPluginCompanion() =
        assertAnchor(BlueprintPlugin.Companion::class, BlueprintPlugin.Target.id)

    @Test
    fun deviceFactoryPluginCompanion() =
        assertAnchor(DeviceFactoryPlugin.Companion::class, DeviceFactoryPlugin.Target.id)
}
