package space.kscience.krig.core.pipeline

import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.featureCatalogOf
import space.kscience.krig.dsl.feature
import space.kscience.dataforge.names.asName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class ExpectedFeatureSpec : FeatureSpec

private class WrongFeatureSpec : FeatureSpec

private class AssemblyFeatureConfig

class PipelineAssemblyTest {
    @Test
    fun mismatchedFeatureSpecReturnsValidationFault() {
        val demoFeature = feature("demo.feature", ExpectedFeatureSpec::class, ::AssemblyFeatureConfig) {}

        val outcome = materializePipelineOutcome(
            features = mapOf("demo.feature".asName() to WrongFeatureSpec()),
            catalog = featureCatalogOf(demoFeature),
        )

        val failure = assertIs<OperationOutcome.Fail>(outcome)
        assertIs<ValidationFault>(failure.fault)
    }

    @OptIn(InternalKrigApi::class)
    @Test
    fun featureScopeConfiguresOperationSpecificTimeouts() {
        val demoFeature = feature("demo.feature", ::AssemblyFeatureConfig) {
            timeout(OperationKinds.Read, 10.milliseconds)
            timeout(OperationKinds.Write, 20.milliseconds)
        }

        val builder = materializePipeline(
            features = mapOf("demo.feature".asName() to object : FeatureSpec {}),
            catalog = featureCatalogOf(demoFeature),
        )

        assertEquals(10.milliseconds, builder.operationSpec(OperationKinds.Read).defaultTimeout)
        assertEquals(20.milliseconds, builder.operationSpec(OperationKinds.Write).defaultTimeout)
    }
}
