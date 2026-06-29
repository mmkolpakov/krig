package space.kscience.krig.flow

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FlowGraphTest {
    @Test
    fun producerBufferConsumerConservesAmountAndRespectsCapacity() {
        val graph = flowGraph {
            producer("source", FlowUnits.Kilogram, FlowRate(10.0))
            buffer("tank", FlowUnits.Kilogram, capacity = FlowAmount(5.0))
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(3.0))
            connect("source", "tank")
            connect("tank", "sink")
        }

        val first = graph.step(1.seconds).snapshot.blocks
        assertEquals(5.0, first.getValue("source".asName()).totalProduced.value)
        assertEquals(3.0, first.getValue("sink".asName()).totalConsumed.value)
        assertEquals(2.0, first.getValue("tank".asName()).inventory?.value)

        val second = graph.step(1.seconds).snapshot.blocks
        val produced = second.getValue("source".asName()).totalProduced.value
        val consumed = second.getValue("sink".asName()).totalConsumed.value
        val inventory = second.getValue("tank".asName()).inventory?.value ?: 0.0
        assertEquals(produced, consumed + inventory)
        assertTrue(inventory <= 5.0)
    }

    @Test
    fun sameGraphStepsRepeatably() {
        fun create(): FlowGraph = flowGraph {
            producer("source", FlowUnits.Kilogram, FlowRate(4.0))
            buffer("tank", FlowUnits.Kilogram, capacity = FlowAmount(10.0), outputLimit = FlowRate(1.5))
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(3.0))
            connect("source", "tank")
            connect("tank", "sink")
        }

        val first = create()
        val second = create()
        repeat(3) {
            assertEquals(first.step(1.seconds), second.step(1.seconds))
        }
    }

    @Test
    fun unitMismatchFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            flowGraph {
                producer("source", FlowUnits.Kilogram, FlowRate(1.0))
                consumer("sink", FlowUnits.Liter, capacity = FlowRate(1.0))
                connect("source", "sink")
            }
        }
    }

    @Test
    fun mixCombinesInputsWithoutLosingMass() {
        val graph = flowGraph {
            producer("left", FlowUnits.Kilogram, FlowRate(1.0))
            producer("right", FlowUnits.Kilogram, FlowRate(2.0))
            mix("mix", FlowUnits.Kilogram, inputs = listOf("left", "right"))
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("left", "mix", targetPort = "left")
            connect("right", "mix", targetPort = "right")
            connect("mix", "sink")
        }

        val snapshot = graph.step(1.seconds).snapshot.blocks
        assertEquals(3.0, snapshot.getValue("sink".asName()).totalConsumed.value)
        assertEquals(0.0, snapshot.getValue("mix".asName()).inventory?.value)
    }

    @Test
    fun emptyBufferSnapshotCarriesUncertainQuality() {
        val graph = flowGraph {
            buffer("tank", FlowUnits.Kilogram, capacity = FlowAmount(5.0))
        }

        val buffer = graph.snapshot().blocks.getValue("tank".asName())
        assertEquals(QualitySeverity.UNCERTAIN, buffer.quality.severity)
        assertEquals("krig.flow.empty-buffer", buffer.quality.code?.id)
    }
}
