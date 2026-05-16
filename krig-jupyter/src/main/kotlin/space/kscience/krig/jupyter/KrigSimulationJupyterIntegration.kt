package space.kscience.krig.jupyter

import org.jetbrains.kotlinx.jupyter.api.libraries.JupyterIntegration

/**
 * `%use krig-simulation` — extends the krig SDK with virtual-time simulation,
 * discrete-event scheduling, resource management, and process DSL.
 *
 * Designed as an opt-in layer on top of the core `%use krig` integration.
 * Imports [space.kscience.krig.simulation] and [space.kscience.krig.concurrency]
 * namespaces so that DeterministicScheduler, ProcessDsl, Resource, and Signal are available
 * without manual imports.
 */
public class KrigSimulationJupyterIntegration : JupyterIntegration() {

    override fun Builder.onLoaded() {
        // Simulation-specific namespaces — the core SDK surface is already
        // imported by `%use krig`.
        import("space.kscience.krig.simulation.*")
        import("space.kscience.krig.concurrency.*")
    }
}
