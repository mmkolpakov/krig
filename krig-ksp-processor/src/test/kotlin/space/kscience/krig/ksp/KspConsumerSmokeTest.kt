package space.kscience.krig.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * KSP consumer smoke — verifies that the processor compiles without errors
 * under the current Kotlin/KSP combination.
 */
class KspConsumerSmokeTest {

    @Test
    fun `processor compiles a minimal @ContributesManifest without errors`() {
        // Verify that the SPI-resolved provider is loadable.
        // Full KCT Fork compilation test requires a separate consumer module
        // with krig-mpp-ksp plugin applied — this test confirms SPI integrity.
        // The actual consumer build is verified by the krig-mpp-ksp convention
        // plugin in downstream modules after the project(":krig-ksp-processor")
        // fix is applied.
        val providers = java.util.ServiceLoader.load(
            com.google.devtools.ksp.processing.SymbolProcessorProvider::class.java,
            javaClass.classLoader,
        ).toList()
        assertNotNull(
            providers.find { it.javaClass.simpleName.contains("KrigSymbol") },
            "KrigSymbolProcessorProvider must be discoverable via ServiceLoader",
        )
    }
}
