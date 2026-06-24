package space.kscience.krig.assembly

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
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
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
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
            reader = acquisitionTagReader(FixedAcquisitionClock) { tag -> metaOf(values.getValue(tag.address)) },
        ).toList()

        assertEquals(listOf("rpm".asName(), "temperature".asName()), observations.map { it.spec.id })
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
            reader = acquisitionTagReader(FixedAcquisitionClock) {
                delay(100.milliseconds)
                metaOf(1.0)
            },
        ).toList().single()

        assertIs<TimeoutFault>(observation.fault)
        assertEquals(null, observation.observed.value)
        assertEquals(QualitySeverity.BAD, observation.observed.quality.severity)
        assertEquals(QualityNamespaces.Acquisition.code("fault-timeout"), observation.observed.quality.code)
    }

    @Test
    fun pollTimerUsesInjectedQualityPolicyForFailures() = runTest {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual")
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE, timeout = 10.milliseconds)
            timer("fast", 10.milliseconds) {
                samples("rpm")
            }
        }
        val policy = QualityPolicy { _, namespace ->
            DataQuality(QualitySeverity.UNCERTAIN, namespace.code("custom"))
        }

        val observation = config.pollTimer(
            timerId = "fast",
            ticks = flowOf(Unit),
            clock = FixedAcquisitionClock,
            reader = acquisitionTagReader(FixedAcquisitionClock) {
                delay(100.milliseconds)
                metaOf(1.0)
            },
            qualityPolicy = policy,
        ).toList().single()

        assertIs<TimeoutFault>(observation.fault)
        assertEquals(QualitySeverity.UNCERTAIN, observation.observed.quality.severity)
        assertEquals(QualityNamespaces.Acquisition.code("custom"), observation.observed.quality.code)
    }

    @Test
    fun pollTimerReadsEachSourceOnceInBatch() = runTest {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual")
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
            tag("temperature").from("stand", "temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "temperature") }
        }
        val batches = mutableListOf<List<Name>>()
        val reader = AcquisitionSourceReader { _, tags ->
            batches += tags.map { it.id }
            tags.associate { it.id to OperationOutcome.Ok(ObservedValue(metaOf(1.0), FixedAcquisitionClock.now(), DataQuality.GOOD)) }
        }

        val observations = config.pollTimer("fast", flowOf(Unit), reader, FixedAcquisitionClock).toList()

        assertEquals(listOf(listOf("rpm".asName(), "temperature".asName())), batches)
        assertEquals(listOf("rpm".asName(), "temperature".asName()), observations.map { it.spec.id })
    }

    @Test
    fun connectorReaderDispatchesEachSourceToItsConnector() = runTest {
        val config = dataAcquisition {
            source("dev", connector = AcquisitionConnectors.KrigDevice)
            source("ext", connector = "external.virtual")
            tag("rpm").from("dev", "rpm", TypeIds.DOUBLE)
            tag("flow").from("ext", "flow", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "flow") }
        }
        val seen = mutableMapOf<Name, List<Name>>()
        fun recordingReader(connector: Name) = AcquisitionSourceReader { source, tags ->
            seen[connector] = tags.map { it.id }
            tags.associate { it.id to OperationOutcome.Ok(ObservedValue(metaOf(1.0), FixedAcquisitionClock.now(), DataQuality.GOOD)) }
        }
        val reader = connectorAcquisitionReader(
            mapOf(
                AcquisitionConnectors.KrigDevice to recordingReader(AcquisitionConnectors.KrigDevice),
                "external.virtual".asName() to recordingReader("external.virtual".asName()),
            ),
        )

        config.pollTimer("fast", flowOf(Unit), reader, FixedAcquisitionClock).toList()

        assertEquals(listOf("rpm".asName()), seen[AcquisitionConnectors.KrigDevice])
        assertEquals(listOf("flow".asName()), seen["external.virtual".asName()])
    }

    @Test
    fun pollTimerDegradesAllTagsWhenSourceTransportFails() = runTest {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual")
            tag("rpm").from("stand", "rpm", TypeIds.DOUBLE)
            tag("temperature").from("stand", "temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "temperature") }
        }
        val reader = AcquisitionSourceReader { _, _ -> throw IOException("link down") }

        val observations = config.pollTimer("fast", flowOf(Unit), reader, FixedAcquisitionClock).toList()

        assertEquals(2, observations.size)
        assertTrue(observations.all { it.fault is TransportFault })
        assertTrue(observations.all { it.observed.value == null })
    }
}
