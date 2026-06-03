package space.kscience.krig.demo

import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.core.contracts.read
import space.kscience.krig.dsl.device

/**
 * Transient driver failures handled by the operation retry policy. The flaky backend lives in
 * [DemoFixtures] ([FlakyPump]); this stays a thin scenario.
 */
suspend fun flakyNetworkDemo() {
    val ctx = demoContext("flaky-network-demo")
    val driver = FlakyPump(failuresBeforeSuccess = 2)
    val pump = device("flakyPump", driver.backend(), ctx) {
        manifest(PumpManifest)
        install(DemoRetry) {
            policy = RetryPolicy(maxAttempts = 2, initialDelay = 5.milliseconds)
        }
    }

    println("=== Flaky network ===")
    println("  rpm after retry: ${pump.read(PumpSpec.rpm)}")
    println("  driver attempts: ${driver.attempts}")

    pump.close()
    ctx.close()
    println("\nDone - flaky network demo complete.")
}
