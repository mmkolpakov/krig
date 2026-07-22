package space.kscience.krig.api.discovery

import space.kscience.krig.assembly.DeviceCatalog
import space.kscience.krig.assembly.DeviceFactoryPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the invariant between the KSP-visible `@TargetId(value = "...", generatedName = "...")` literals and the
 * runtime `Target: ContributionTarget<*>` ids without depending on runtime annotation
 * reflection. `@TargetId` is a compile-time/KSP annotation with BINARY retention.
 */
class TargetIdRuntimeConsistencyTest {

    @Test
    fun targetIdsMatchRuntimeContributionTargets() {
        val expected = mapOf(
            "PipelineFeatureContributions" to "krig.pipeline-feature",
            "ProtocolContributions" to "krig.protocol",
            "ActionHandlerContributions" to "krig.action-handler",
            "DeviceCatalog.Companion" to "krig.manifest",
            "DeviceFactoryPlugin.Companion" to "krig.factory",
        )
        val actual = mapOf(
            "PipelineFeatureContributions" to PipelineFeatureContributions.Target.id,
            "ProtocolContributions" to ProtocolContributions.Target.id,
            "ActionHandlerContributions" to ActionHandlerContributions.Target.id,
            "DeviceCatalog.Companion" to DeviceCatalog.Target.id,
            "DeviceFactoryPlugin.Companion" to DeviceFactoryPlugin.Target.id,
        )
        assertEquals(expected, actual)
    }
}
