package space.kscience.krig.jupyter

import org.jetbrains.kotlinx.jupyter.api.libraries.JupyterIntegration

/**
 * Compatibility integration for a future dedicated `krig-simulation`
 * Kotlin Notebook descriptor.
 *
 * The local `krig.json` descriptor loads [KrigJupyterIntegration], which already
 * imports these namespaces for a one-cell checkout experience.
 */
public class KrigSimulationJupyterIntegration : JupyterIntegration() {

    override fun Builder.onLoaded() {
        import("space.kscience.krig.simulation.*")
        import("space.kscience.krig.concurrency.*")
    }
}
