package space.kscience.krig.assembly

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.result.ok
import space.kscience.krig.core.contracts.metaOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private object FixedAcquisitionClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(123)
}

class AcquisitionPollingTest {
    @Test
    fun pollTimerEmitsConfiguredTagsInTimerOrder() = runTest {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual")
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE).toTarget("pump", "rpm")
            tag("temperature").from("stand", "temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) {
                samples("rpm", "temperature")
            }
        }
        val values = mapOf("rpm" to 1_200.0, "temperature" to 42.0)

        val observations = config.pollTimer(
            timerId = "fast",
            ticks = flowOf(Unit),
            clock = FixedAcquisitionClock,
            reader = { tag -> ok(metaOf(values.getValue(tag.address))) },
        ).toList()

        assertEquals(listOf("rpm".asName(), "temperature".asName()), observations.map { it.tag.id })
        assertTrue(observations.all { it.isOk })
        assertEquals(1_200.0, MetaConverter.double.read(observations[0].observed.value!!))
        assertEquals(FixedAcquisitionClock.now(), observations[0].observed.time)
    }

    @Test
    fun pollTimerTurnsTagTimeoutIntoDegradedObservation() = runTest {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual")
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE, timeout = 10.milliseconds)
            timer("fast", 10.milliseconds) {
                samples("rpm")
            }
        }

        val observation = config.pollTimer(
            timerId = "fast",
            ticks = flowOf(Unit),
            clock = FixedAcquisitionClock,
            reader = {
                delay(100.milliseconds)
                ok(metaOf(1.0))
            },
        ).toList().single()

        assertIs<TimeoutFault>(observation.fault)
        assertEquals(null, observation.observed.value)
        assertEquals(QualitySeverity.BAD, observation.observed.quality.severity)
    }
}
