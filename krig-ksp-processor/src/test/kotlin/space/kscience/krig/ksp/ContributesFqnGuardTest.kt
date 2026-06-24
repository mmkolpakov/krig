package space.kscience.krig.ksp

import space.kscience.krig.api.annotations.Contributes
import space.kscience.krig.api.discovery.TargetId
import space.kscience.krig.assembly.ContributesManifest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test-guard for [ContributesAggregator]'s hardcoded annotation FQNs.
 *
 * The processor matches annotations by string because it runs on a separate classpath and must
 * not depend (on its main compilation) on the runtime artifacts it processes — a `compileOnly`
 * dependency on `krig-assembly` would form a Gradle cycle (assembly applies this very processor).
 * This guard runs on the *test* classpath (acyclic) and fails the build if any annotation is moved
 * or renamed, turning a silent aggregation break into a compile/test failure.
 */
class ContributesFqnGuardTest {

    @Test
    fun aggregatorFqnsMatchRealAnnotations() {
        assertEquals(
            Contributes::class.qualifiedName,
            ContributesAggregator.CONTRIBUTES_FQN,
            "Contributes moved/renamed — update ContributesAggregator.CONTRIBUTES_FQN.",
        )
        assertEquals(
            TargetId::class.qualifiedName,
            ContributesAggregator.TARGET_ID_FQN,
            "TargetId moved/renamed — update ContributesAggregator.TARGET_ID_FQN.",
        )
        assertEquals(
            ContributesManifest::class.qualifiedName,
            ContributesAggregator.CONTRIBUTES_MANIFEST_FQN,
            "ContributesManifest moved/renamed — update ContributesAggregator.CONTRIBUTES_MANIFEST_FQN.",
        )
    }
}
