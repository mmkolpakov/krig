package space.kscience.krig.demo

import kotlinx.coroutines.cancel
import space.kscience.krig.concurrency.Resource
import space.kscience.krig.concurrency.ResourcePreemptedException
import space.kscience.krig.concurrency.ResourcePreemptionPolicies
import space.kscience.krig.concurrency.ResourcePriority
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.simulation.DeterministicScheduler
import space.kscience.krig.simulation.hold
import space.kscience.krig.simulation.process
import space.kscience.krig.simulation.request
import space.kscience.krig.simulation.simulationScope
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic process DSL over virtual time.
 */
suspend fun simulationProcessDemo() {
    val scheduler = DeterministicScheduler()
    val scope = simulationScope(scheduler)
    val ctx = demoContext("simulation-process-demo")
    val pump = device(
        name = "simPump",
        backend = pumpBackend(),
        runtime = DeviceRuntime(
            context = ctx,
            clock = scheduler.asClock(),
            timeSource = scheduler.asTimeSource(),
        ),
    ) {
        blueprint(PumpBlueprint)
    }
    val bus = Resource(
        name = "fieldbus",
        capacity = 1,
        preemption = ResourcePreemptionPolicies.Priority,
    )
    val log = mutableListOf<String>()

    try {
        val poll = scope.process("poll-cycle") {
            log += "start@${scheduler.currentTimeMs}"
            try {
                request(bus, priority = ResourcePriority.Low) {
                    hold(50.milliseconds)
                    pump.write(PumpSpec.rpm, 1_250.0)
                    log += "write@${scheduler.currentTimeMs}"
                }
            } catch (_: ResourcePreemptedException) {
                log += "poll-preempted@${scheduler.currentTimeMs}"
            }
            hold(25.milliseconds)
            log += "publish@${scheduler.currentTimeMs}"
        }
        val emergency = scope.process("emergency-stop") {
            hold(10.milliseconds)
            request(bus, priority = ResourcePriority.High) {
                pump.write(PumpSpec.rpm, 0.0)
                log += "stop@${scheduler.currentTimeMs}"
            }
        }

        scheduler.advanceBy(100.milliseconds)
        poll.join()
        emergency.join()

        println("=== Simulation process ===")
        println("  log: $log")
        println("  simulated rpm: ${pump.read(PumpSpec.rpm)}")
    } finally {
        pump.close()
        ctx.close()
        scope.cancel()
    }
    println("\nDone - simulation process demo complete.")
}
