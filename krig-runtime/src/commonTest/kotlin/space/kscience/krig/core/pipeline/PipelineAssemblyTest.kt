package space.kscience.krig.core.pipeline

import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.features.pipelineFeatureCatalogOf
import space.kscience.krig.dsl.pipelineFeature
import space.kscience.dataforge.names.asName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class ExpectedPipelineFeatureSpec : PipelineFeatureSpec

private class WrongPipelineFeatureSpec : PipelineFeatureSpec

private class AssemblyFeatureConfig

class PipelineAssemblyTest {
    @Test
    fun mismatchedPipelineFeatureSpecReturnsValidationFault() {
        val demoFeature = pipelineFeature("demo.feature", ExpectedPipelineFeatureSpec::class, ::AssemblyFeatureConfig) {}

        val outcome = materializePipelineOutcome(
            features = mapOf("demo.feature".asName() to WrongPipelineFeatureSpec()),
            catalog = pipelineFeatureCatalogOf(demoFeature),
        )

        val failure = assertIs<OperationOutcome.Fail>(outcome)
        assertIs<ValidationFault>(failure.fault)
    }

    @OptIn(InternalKrigApi::class)
    @Test
    fun pipelineFeatureScopeConfiguresOperationSpecificTimeouts() {
        val demoFeature = pipelineFeature("demo.feature", ::AssemblyFeatureConfig) {
            timeout(OperationKinds.Read, 10.milliseconds)
            timeout(OperationKinds.Write, 20.milliseconds)
        }

        val builder = materializePipeline(
            features = mapOf("demo.feature".asName() to object : PipelineFeatureSpec {}),
            catalog = pipelineFeatureCatalogOf(demoFeature),
        )

        assertEquals(10.milliseconds, builder.operationSpec(OperationKinds.Read).defaultTimeout)
        assertEquals(20.milliseconds, builder.operationSpec(OperationKinds.Write).defaultTimeout)
    }
}
