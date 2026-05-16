package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.sampleWithHold
import space.kscience.krig.dsl.typedSamples

/**
 * Primitive sampler plus Flow/ZOH view for UI-rate streaming.
 */
public suspend fun streamingDemo(): Unit = supervisorScope {
    val ctx = demoContext("streaming-demo")
    val pump = device("streamingPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }
    val sampler = pump.sampler(PumpSpec.rpm) as RingDoubleSampler

    println("=== Streaming ===")
    val held = collectLiveSamples(
        pump.typedSamples(AnonymousPrincipal, PumpSpec.rpm)
            .sampleWithHold(10.milliseconds)
            .take(3),
    ) {
        pump.write(PumpSpec.rpm, 900.0)
    }

    println("  latest rpm: ${sampler.latestDouble()}")
    println("  snapshot: ${sampler.snapshotDoubleArray().joinToString(prefix = "[", postfix = "]")}")
    println("  held samples: $held")

    pump.close()
    ctx.close()
    println("\nDone - streaming demo complete.")
}

private suspend fun <T> collectLiveSamples(flow: Flow<T>, publish: suspend () -> Unit): List<T> =
    coroutineScope {
        val samples = async(start = CoroutineStart.UNDISPATCHED) { flow.toList() }
        yield()
        publish()
        samples.await()
    }
