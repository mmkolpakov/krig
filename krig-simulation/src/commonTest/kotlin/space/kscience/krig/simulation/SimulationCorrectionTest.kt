package space.kscience.krig.simulation

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SimulationCorrectionTest {

    @Test
    fun stateVectorCopiesInputCoordinates() {
        val source = mutableListOf(1.0, 2.0)
        val vector = SimulationStateVector(source)

        source[0] = 99.0

        assertEquals(listOf(1.0, 2.0), vector.coordinates)
        assertEquals(2, vector.size)
        assertEquals(2.0, vector[1])
    }

    @Test
    fun stateVectorRejectsNonFiniteCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            SimulationStateVector.of(1.0, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulationStateVector.of(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun projectionBuildsCheckpointWithVectorAndMetaState() {
        val projection = StateVectorProjection<Pair<Double, Double>> { (left, right) ->
            SimulationStateVector.of(left, right)
        }
        val time = Instant.fromEpochMilliseconds(42)

        val checkpoint = projection.checkpoint(time, 1.0 to 2.0, Meta.EMPTY)

        assertEquals(time, checkpoint.time)
        assertEquals(SimulationStateVector.of(1.0, 2.0), checkpoint.vector)
        assertEquals(Meta.EMPTY, checkpoint.state)
    }

    @Test
    fun noopAssimilationPolicyKeepsStateAndMarksNoop() = runTest {
        val checkpoint = SimulationCheckpoint(time = Instant.fromEpochMilliseconds(10))
        val result = AssimilationPolicies.noop<String>().correct("state", checkpoint)

        assertEquals("state", result.state)
        assertEquals(checkpoint, result.checkpoint)
        assertEquals(SimulationCorrectionStatus.Noop, result.status)
        assertEquals(Meta.EMPTY, result.diagnostics)
    }

    @Test
    fun sessionCapturesCheckpointAtCurrentSimulationTime() = runTest {
        val scheduler = DeterministicScheduler(initialTimeMs = 1_000)
        val session = SimulationSession(
            scheduler = scheduler,
            devices = emptyList(),
            stepDuration = 25.milliseconds,
        )
        val source = SimulationCheckpointSource { time ->
            SimulationCheckpoint(
                time = time,
                vector = SimulationStateVector.of(time.toEpochMilliseconds().toDouble()),
            )
        }

        session.step()
        val checkpoint = session.captureCheckpoint(source)

        assertEquals(Instant.fromEpochMilliseconds(1_025), checkpoint.time)
        val vector = assertIs<SimulationStateVector>(checkpoint.vector)
        assertEquals(1_025.0, vector[0])
    }
}
