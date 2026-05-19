package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.sampleWithHold
import space.kscience.krig.dsl.sharedTicks
import space.kscience.krig.dsl.typedSamples
import kotlin.time.Duration.Companion.milliseconds

/**
 * One timer shared by polling, control, and UI-rate sampling.
 */
suspend fun sharedTimerControlDemo(): Unit = supervisorScope {
    val ctx = demoContext("shared-timer-control-demo")
    val pump = device("sharedTimerPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }
    val ticks = sharedTicks(pump.deviceScope, 10.milliseconds)

    println("=== Shared timer control ===")
    val held = async(start = CoroutineStart.UNDISPATCHED) {
        pump.typedSamples(AnonymousPrincipal, PumpSpec.rpm)
            .sampleWithHold(ticks)
            .take(3)
            .toList()
    }
    val controller = async(start = CoroutineStart.UNDISPATCHED) {
        ticks.take(3).collect { pump.write(PumpSpec.rpm, pump.read(PumpSpec.rpm) + 100.0) }
    }
    yield()
    pump.write(PumpSpec.rpm, 900.0)
    controller.await()

    println("  held samples: ${held.await()}")
    println("  final rpm: ${pump.read(PumpSpec.rpm)}")

    pump.close()
    ctx.close()
    println("\nDone - shared timer control demo complete.")
}
