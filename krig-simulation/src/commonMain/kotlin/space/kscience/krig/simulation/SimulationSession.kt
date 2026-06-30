package space.kscience.krig.simulation

import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.SteppedBackend
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Step-by-step coordinator for a group of devices on a [SimulationScheduler].
 * Each [step] advances virtual time by [stepDuration] and steps every registered
 * [SteppedBackend] — hardware transports have no time-advancement contract and
 * do not belong here.
 *
 * ```kotlin
 * val session = SimulationSession(DeterministicScheduler(), listOf(regulator, plant), 10.milliseconds)
 * repeat(1000) { session.step() }
 * ```
 */
public class SimulationSession(
    private val scheduler: SimulationScheduler,
    public val devices: List<Device>,
    public val stepDuration: Duration,
    private val connections: List<SteppedBackend> = emptyList(),
) {
    public val currentTimeMs: Long get() = scheduler.currentTimeMs
    public val currentInstant: Instant get() = scheduler.asClock().now()

    public suspend fun step() {
        scheduler.advanceBy(stepDuration)
        connections.forEach { it.step(stepDuration) }
    }

}
