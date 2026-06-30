package space.kscience.krig.demo

import space.kscience.krig.simulation.AssimilationPolicy
import space.kscience.krig.simulation.DeterministicScheduler
import space.kscience.krig.simulation.SimulationCheckpointSource
import space.kscience.krig.simulation.SimulationCorrection
import space.kscience.krig.simulation.SimulationCorrectionStatus
import space.kscience.krig.simulation.SimulationSession
import space.kscience.krig.simulation.SimulationStateVector
import space.kscience.krig.simulation.StateVectorProjection
import space.kscience.krig.simulation.captureCheckpoint
import space.kscience.krig.simulation.checkpoint
import kotlin.time.Duration.Companion.milliseconds

internal data class CorrectableSimulationSnapshot(
    val checkpointTimeMs: Long,
    val predictedTemperature: Double,
    val observedTemperature: Double,
    val correctedTemperature: Double,
    val status: SimulationCorrectionStatus,
)

/** Minimal correctable-simulation SPI demo: checkpoint capture plus explicit assimilation policy. */
suspend fun correctableSimulationDemo() {
    val snapshot = correctableSimulationSnapshot()

    println("=== Correctable simulation ===")
    println("  checkpoint: ${snapshot.checkpointTimeMs} ms")
    println("  predicted: ${snapshot.predictedTemperature}")
    println("  observed: ${snapshot.observedTemperature}")
    println("  corrected: ${snapshot.correctedTemperature}")
    println("  status: ${snapshot.status}")
    println("\nDone - correctable simulation demo complete.")
}

internal suspend fun correctableSimulationSnapshot(): CorrectableSimulationSnapshot {
    val scheduler = DeterministicScheduler(initialTimeMs = 10_000)
    val session = SimulationSession(
        scheduler = scheduler,
        devices = emptyList(),
        stepDuration = 100.milliseconds,
    )
    val projection = StateVectorProjection<Double> { temperature -> SimulationStateVector.of(temperature) }
    val observedTemperature = 92.0
    val source = SimulationCheckpointSource { time -> projection.checkpoint(time, observedTemperature) }
    val policy = AssimilationPolicy<Double> { predicted, checkpoint ->
        val observed = checkpoint.vector?.get(0)
        if (observed == null) {
            SimulationCorrection(predicted, checkpoint, SimulationCorrectionStatus.Rejected)
        } else {
            SimulationCorrection(
                state = predicted + (observed - predicted) * 0.5,
                checkpoint = checkpoint,
                status = SimulationCorrectionStatus.Accepted,
            )
        }
    }
    val predictedTemperature = 100.0

    session.step()
    val checkpoint = session.captureCheckpoint(source)
    val corrected = policy.correct(predictedTemperature, checkpoint)

    return CorrectableSimulationSnapshot(
        checkpointTimeMs = checkpoint.time.toEpochMilliseconds(),
        predictedTemperature = predictedTemperature,
        observedTemperature = observedTemperature,
        correctedTemperature = corrected.state,
        status = corrected.status,
    )
}
