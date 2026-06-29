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
    fun reactionConvertsInputByYield() {
        val graph = flowGraph {
            producer("feed", FlowUnits.Kilogram, FlowRate(10.0))
            reaction("reactor", FlowUnits.Kilogram, yield = FlowRatio(0.5))
            consumer("product", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("feed", "reactor")
            connect("reactor", "product")
        }

        val snapshot = graph.step(1.seconds).snapshot.blocks
        assertEquals(10.0, snapshot.getValue("feed".asName()).totalProduced.value)
        assertEquals(10.0, snapshot.getValue("reactor".asName()).lastInput.value)
        assertEquals(5.0, snapshot.getValue("reactor".asName()).lastOutput.value)
        assertEquals(5.0, snapshot.getValue("product".asName()).totalConsumed.value)
        assertEquals(0.0, snapshot.getValue("reactor".asName()).inventory?.value)
    }

    @Test
    fun separatorSplitsInputByNormalizedShares() {
        val graph = flowGraph {
            producer("feed", FlowUnits.Kilogram, FlowRate(10.0))
            separate(
                id = "split",
                unit = FlowUnits.Kilogram,
                outputs = mapOf("left" to FlowRatio(0.7), "right" to FlowRatio(0.3)),
            )
            consumer("leftSink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            consumer("rightSink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("feed", "split")
            connect("split", "leftSink", sourcePort = "left")
            connect("split", "rightSink", sourcePort = "right")
        }

        val snapshot = graph.step(1.seconds).snapshot.blocks
        assertEquals(7.0, snapshot.getValue("leftSink".asName()).totalConsumed.value)
        assertEquals(3.0, snapshot.getValue("rightSink".asName()).totalConsumed.value)
        assertEquals(10.0, snapshot.getValue("split".asName()).lastInput.value)
        assertEquals(10.0, snapshot.getValue("split".asName()).lastOutput.value)
        assertEquals(0.0, snapshot.getValue("split".asName()).inventory?.value)
    }

    @Test
    fun limiterCapsOutputAndKeepsBacklog() {
        val graph = flowGraph {
            producer("feed", FlowUnits.Kilogram, FlowRate(10.0))
            limited("pipe", FlowUnits.Kilogram, outputLimit = FlowRate(4.0))
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("feed", "pipe")
            connect("pipe", "sink")
        }

        val first = graph.step(1.seconds).snapshot.blocks
        assertEquals(10.0, first.getValue("feed".asName()).totalProduced.value)
        assertEquals(4.0, first.getValue("sink".asName()).totalConsumed.value)
        assertEquals(6.0, first.getValue("pipe".asName()).inventory?.value)

        val second = graph.step(1.seconds).snapshot.blocks
        assertEquals(20.0, second.getValue("feed".asName()).totalProduced.value)
        assertEquals(8.0, second.getValue("sink".asName()).totalConsumed.value)
        assertEquals(12.0, second.getValue("pipe".asName()).inventory?.value)
    }

    @Test
    fun delayLinePublishesInputAfterConfiguredTicks() {
        val graph = flowGraph {
            producer("feed", FlowUnits.Kilogram, FlowRate(5.0))
            delayed("line", FlowUnits.Kilogram, delaySteps = 2)
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("feed", "line")
            connect("line", "sink")
        }

        val first = graph.step(1.seconds).snapshot.blocks
        assertEquals(0.0, first.getValue("sink".asName()).totalConsumed.value)
        assertEquals(5.0, first.getValue("line".asName()).inventory?.value)
        assertEquals(QualitySeverity.UNCERTAIN, first.getValue("line".asName()).quality.severity)
        assertEquals("krig.flow.delayed-material", first.getValue("line".asName()).quality.code?.id)

        val second = graph.step(1.seconds).snapshot.blocks
        assertEquals(0.0, second.getValue("sink".asName()).totalConsumed.value)
        assertEquals(10.0, second.getValue("line".asName()).inventory?.value)

        val third = graph.step(1.seconds).snapshot.blocks
        assertEquals(5.0, third.getValue("sink".asName()).totalConsumed.value)
        assertEquals(10.0, third.getValue("line".asName()).inventory?.value)
    }

    @Test
    fun extendedBlocksStepRepeatably() {
        fun create(): FlowGraph = flowGraph {
            producer("feed", FlowUnits.Kilogram, FlowRate(6.0))
            reaction("reactor", FlowUnits.Kilogram, yield = FlowRatio(0.5))
            limited("pipe", FlowUnits.Kilogram, outputLimit = FlowRate(2.0))
            delayed("line", FlowUnits.Kilogram, delaySteps = 1)
            consumer("sink", FlowUnits.Kilogram, capacity = FlowRate(10.0))
            connect("feed", "reactor")
            connect("reactor", "pipe")
            connect("pipe", "line")
            connect("line", "sink")
        }

        val first = create()
        val second = create()
        repeat(4) {
            assertEquals(first.step(1.seconds), second.step(1.seconds))
        }
    }

    @Test
    fun separatorRejectsUnnormalizedShares() {
        assertFailsWith<IllegalArgumentException> {
            flowGraph {
                separate(
                    id = "split",
                    unit = FlowUnits.Kilogram,
                    outputs = mapOf("left" to FlowRatio(0.7), "right" to FlowRatio(0.4)),
                )
            }
        }
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
