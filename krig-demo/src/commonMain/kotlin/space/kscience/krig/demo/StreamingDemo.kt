package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.core.contracts.sampling.requireDoubleSampler
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.sampleWithHold
import space.kscience.krig.dsl.sharedTicks
import space.kscience.krig.dsl.typedSamples

/**
 * Primitive sampler plus Flow/ZOH view for UI-rate streaming.
 */
suspend fun streamingDemo(): Unit = supervisorScope {
    val ctx = demoContext("streaming-demo")
    val pump = device("streamingPump", pumpBackend(), ctx) {
        manifest(PumpManifest)
    }
    val sampler = pump.requireDoubleSampler(PumpSpec.rpm)

    println("=== Streaming ===")
    val ticks = sharedTicks(pump.deviceScope, 10.milliseconds)
    val rpmSamples = pump.typedSamples(AnonymousPrincipal, PumpSpec.rpm)
    val heldRpm = async(start = CoroutineStart.UNDISPATCHED) {
        rpmSamples.sampleWithHold(ticks).take(3).toList()
    }
    val heldLoad = async(start = CoroutineStart.UNDISPATCHED) {
        rpmSamples.map { it / 3_000.0 }.sampleWithHold(ticks).take(3).toList()
    }
    val publisher = launch(start = CoroutineStart.UNDISPATCHED) {
        delay(1.milliseconds)
        var value = 900.0
        while (true) {
            pump.write(PumpSpec.rpm, value)
            value += 10.0
            delay(5.milliseconds)
        }
    }

    val rpmHeld = heldRpm.await()
    val loadHeld = heldLoad.await()
    publisher.cancelAndJoin()
    println("  latest rpm: ${sampler.latestDoubleOrNaN()}")
    println("  snapshot: ${sampler.snapshotDoubleArray().joinToString(prefix = "[", postfix = "]")}")
    println("  held rpm samples: $rpmHeld")
    println("  held load samples: $loadHeld")

    pump.close()
    ctx.close()
    println("\nDone - streaming demo complete.")
}
