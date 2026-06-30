package space.kscience.krig.simulation

import space.kscience.dataforge.meta.Meta
import kotlin.time.Instant

/**
 * Numeric state projection used by correction and assimilation policies.
 */
public class SimulationStateVector(coordinates: List<Double>) {
    public val coordinates: List<Double> = coordinates.toList()

    init {
        require(this.coordinates.all { it.isFinite() }) {
            "Simulation state vector coordinates must be finite."
        }
    }

    public val size: Int get() = coordinates.size

    public operator fun get(index: Int): Double = coordinates[index]

    override fun equals(other: Any?): Boolean =
        this === other || other is SimulationStateVector && coordinates == other.coordinates

    override fun hashCode(): Int = coordinates.hashCode()

    override fun toString(): String = "SimulationStateVector(coordinates=$coordinates)"

    public companion object {
        public fun of(vararg coordinates: Double): SimulationStateVector =
            SimulationStateVector(coordinates.asList())
    }
}

/**
 * Snapshot of a simulated model at a deterministic simulation timestamp.
 *
 * [state] keeps the schema-less DataForge projection for notebooks, replay and
 * external tools. [vector] is an optional typed numeric projection for
 * correction algorithms.
 */
public data class SimulationCheckpoint(
    public val time: Instant,
    public val state: Meta = Meta.EMPTY,
    public val vector: SimulationStateVector? = null,
)

/**
 * Captures a simulation checkpoint at the supplied simulation [time].
 */
public fun interface SimulationCheckpointSource {
    public suspend fun captureCheckpoint(time: Instant): SimulationCheckpoint
}

/**
 * Projects a domain-specific simulation state into a numeric vector.
 */
public fun interface StateVectorProjection<T> {
    public fun project(state: T): SimulationStateVector
}

/**
 * Result of applying a correction policy to a simulation state.
 */
public data class SimulationCorrection<T>(
    public val state: T,
    public val checkpoint: SimulationCheckpoint,
    public val status: SimulationCorrectionStatus,
    public val diagnostics: Meta = Meta.EMPTY,
)

public enum class SimulationCorrectionStatus {
    Accepted,
    Rejected,
    Noop,
}

/**
 * Extension point for data-assimilation, replay correction and digital-twin
 * reconciliation strategies.
 */
public fun interface AssimilationPolicy<T> {
    public suspend fun correct(state: T, checkpoint: SimulationCheckpoint): SimulationCorrection<T>
}

public object AssimilationPolicies {
    public fun <T> noop(): AssimilationPolicy<T> = AssimilationPolicy { state, checkpoint ->
        SimulationCorrection(
            state = state,
            checkpoint = checkpoint,
            status = SimulationCorrectionStatus.Noop,
        )
    }
}

/**
 * Builds a checkpoint by applying this projection to [state].
 */
public fun <T> StateVectorProjection<T>.checkpoint(
    time: Instant,
    state: T,
    meta: Meta = Meta.EMPTY,
): SimulationCheckpoint = SimulationCheckpoint(
    time = time,
    state = meta,
    vector = project(state),
)

/**
 * Captures a checkpoint at the current simulation-clock time.
 */
public suspend fun SimulationSession.captureCheckpoint(
    source: SimulationCheckpointSource,
): SimulationCheckpoint = source.captureCheckpoint(currentInstant)
