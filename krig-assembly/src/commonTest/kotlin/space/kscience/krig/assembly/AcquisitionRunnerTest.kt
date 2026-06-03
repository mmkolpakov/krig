package space.kscience.krig.assembly

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.operations.ClockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AcquisitionRunnerTest {

    private fun constantReader(values: Map<String, Double>): AcquisitionSourceReader =
        AcquisitionSourceReader { _, tags ->
            tags.associate { tag ->
                tag.id to OperationOutcome.Ok(
                    ObservedValue(metaOf(values.getValue(tag.address)), ClockState().clock.now(), DataQuality.GOOD),
                )
            }
        }

    @Test
    fun runnerEmitsConfiguredTagsForSingleTimer() = runTest {
        val config = dataAcquisition {
            source("stand", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
            tag("temperature").from("stand", "temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "temperature") }
        }

        val observations = config.runner(constantReader(mapOf("rpm" to 1_200.0, "temperature" to 42.0)))
            .observations(ClockState())
            .take(2)
            .toList()

        assertEquals(listOf("rpm".asName(), "temperature".asName()), observations.map { it.spec.id })
        assertTrue(observations.all { it.isOk })
        assertEquals(1_200.0, MetaConverter.double.read(observations[0].observed.value!!))
    }

    @Test
    fun runnerMergesEveryTimer() = runTest {
        val config = dataAcquisition {
            source("stand", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
            tag("level").from("stand", "level", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm") }
            timer("slow", 20.milliseconds) { samples("level") }
        }

        val seen = mutableSetOf<Name>()
        val expected = setOf("rpm".asName(), "level".asName())
        config.runner(constantReader(mapOf("rpm" to 1.0, "level" to 2.0)))
            .observations(ClockState())
            .takeWhile { obs ->
                seen += obs.spec.id
                !seen.containsAll(expected)
            }
            .collect { }

        assertTrue(seen.containsAll(expected), "both timers contributed observations: $seen")
    }
}
