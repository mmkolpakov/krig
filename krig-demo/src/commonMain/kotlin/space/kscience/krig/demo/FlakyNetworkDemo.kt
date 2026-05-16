package space.kscience.krig.demo

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.typed.typedBackend
import space.kscience.krig.dsl.device

/**
 * Transient driver failures handled by the typed pipeline retry policy.
 */
public suspend fun flakyNetworkDemo() {
    val ctx = demoContext("flaky-network-demo")
    val driver = FlakyPumpDriver()
    val pump = device("flakyPump", driver.backend(), ctx) {
        blueprint(PumpBlueprint)
        install(DemoRetryInstaller) {
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

    fun backend() = typedBackend {
        var rpm = 1_200.0

        reader(PumpSpec.rpm) {
            attempts += 1
            delay(10.milliseconds)
            if (attempts < 3) {
                throw DeviceFaultException(TimeoutFault())
            }
            rpm
        }
        writer(PumpSpec.rpm) { value -> rpm = value }
        reader(PumpSpec.load) { rpm / 3_000.0 }
        action(PumpSpec.command) { command -> "ack:$command" }
    }
}
