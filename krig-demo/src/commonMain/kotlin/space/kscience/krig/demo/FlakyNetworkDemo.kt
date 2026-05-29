package space.kscience.krig.demo

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.dsl.device

/**
 * Transient driver failures handled by the operation retry policy.
 */
suspend fun flakyNetworkDemo() {
    val ctx = demoContext("flaky-network-demo")
    val driver = FlakyPumpDriver()
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

private class FlakyPumpDriver {
    var attempts: Int = 0
        private set

    fun backend() = backend {
        var rpm = 1_200.0

        reader(PumpSpec.rpm) {
            attempts += 1
            delay(10.milliseconds)
            if (attempts < 3) {
                throw OperationFaultException(TimeoutFault())
            }
            rpm
        }
        writer(PumpSpec.rpm) { value -> rpm = value }
        reader(PumpSpec.load) { rpm / 3_000.0 }
        action(PumpSpec.command) { command -> "ack:$command" }
    }
}
