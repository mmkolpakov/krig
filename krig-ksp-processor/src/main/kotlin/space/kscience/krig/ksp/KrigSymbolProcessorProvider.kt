package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * SPI entry point for the krig KSP processor.
 * Registered via `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 */
public class KrigSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KrigSymbolProcessor(environment)
}
