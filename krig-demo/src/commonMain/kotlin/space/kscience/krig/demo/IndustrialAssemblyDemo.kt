package space.kscience.krig.demo

import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.core.contracts.*
import space.kscience.krig.dsl.device
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spec-first industrial assembly: reusable Manifest, explicit backend, typed access.
 * Contract/manifest/backend live in [DemoFixtures]; this stays a thin scenario.
 */
suspend fun industrialAssemblyDemo() {
    val ctx = demoContext("industrial-demo")
    val pump = device("mainPump", pumpBackend(), ctx) {
        manifest(PumpManifest)
        install(DemoRetry) {
            policy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds)
        }
    }

    pump.write(PumpSpec.rpm, 1_200.0)
    println("=== Industrial assembly ===")
    println("  rpm: ${pump.read(PumpSpec.rpm)}")
    println("  load: ${pump.read(PumpSpec.load)}")
    println("  command: ${pump.execute(PumpSpec.command, "prime")}")

    pump.close()
    ctx.close()
    println("\nDone - industrial assembly demo complete.")
}
