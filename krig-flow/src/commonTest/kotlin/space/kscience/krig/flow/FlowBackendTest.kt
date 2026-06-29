package space.kscience.krig.flow

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.typed.readObservedOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class FlowBackendTest {
    @Test
    fun steppedBackendPreservesBufferQuality() = runTest {
        val graph = flowGraph {
            producer("source", FlowUnits.Kilogram, FlowRate(1.0))
            buffer("tank", FlowUnits.Kilogram, capacity = FlowAmount(5.0))
            connect("source", "tank")
        }
        val backend = graph.toSteppedBackend()
        val inventory = FlowPropertyContracts.inventory("tank".asName())

        val empty = backend.readObservedOutcome(inventory).getOrThrow()
        assertEquals(QualitySeverity.UNCERTAIN, empty.quality.severity)
        assertEquals(0.0, empty.value)

        backend.step(1.seconds)
        val filled = backend.readObservedOutcome(inventory).getOrThrow()
        assertEquals(QualitySeverity.GOOD, filled.quality.severity)
        assertEquals(1.0, filled.value)
    }
}
