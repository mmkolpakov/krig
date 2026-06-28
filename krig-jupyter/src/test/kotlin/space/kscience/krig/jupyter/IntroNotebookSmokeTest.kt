@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
)

package space.kscience.krig.jupyter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.assembly.DeviceCatalog
import space.kscience.krig.assembly.findManifest
import space.kscience.krig.assembly.registerManifests
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.core.runtime.MutableDeviceHub
import space.kscience.krig.core.state.flowHistory
import space.kscience.krig.core.state.propertyHistory
import space.kscience.krig.core.timetravel.replayLog
import space.kscience.krig.core.timetravel.timeline
import space.kscience.krig.dsl.device
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.simulation.DeterministicScheduler
import space.kscience.krig.simulation.hold
import space.kscience.krig.simulation.process

/** Executes the main `krig-intro.ipynb` walkthrough as JVM code against the current SDK surface. */
class IntroNotebookSmokeTest {

    @Test
    fun introNotebookWalkthroughStillRuns() = runBlocking {
        val thermoContext = krigNotebookContext("intro-notebook-thermo") {
            plugin(DeviceCatalog)
        }
        val thermoManifest = manifestOf("thermo", NotebookThermoContract)
        thermoContext.registerManifests(listOf(thermoManifest))
        var temperature = 22.0
        var setpoint = 20.0
        val thermoBackend = deviceBackend {
            reader(NotebookThermoContract.temperature) { temperature }
            writer(NotebookThermoContract.temperature) { value -> temperature = value }
            reader(NotebookThermoContract.setpoint) { setpoint }
            writer(NotebookThermoContract.setpoint) { value -> setpoint = value }
            action(NotebookThermoContract.reset) {
                temperature = 22.0
                null
            }
        }

        val thermo = device("thermo", thermoBackend, thermoContext) {
            manifest(thermoManifest)
        }

        try {
            assertEquals(thermoManifest, thermoContext.findManifest("thermo".asName()))
            assertTrue("temperature".asName() in thermo.propertyDescriptors.keys)
            assertTrue("reset".asName() in thermo.actionDescriptors.keys)

            assertTrue(thermo.readPropertyOutcome("temperature".asName()) is OperationOutcome.Ok)
            assertTrue(thermo.writeOutcome(NotebookThermoContract.temperature, 24.0) is OperationOutcome.Ok)
            val typedRead = thermo.readOutcome(NotebookThermoContract.temperature)
            assertTrue(typedRead is OperationOutcome.Ok)
            assertEquals(24.0, typedRead.value)

            thermo.writeProperty("temperature".asName(), metaOf(25.0))
            assertEquals(25.0, MetaConverter.double.read(thermo.readProperty("temperature".asName())))

            exerciseHubCell()
            exerciseTimelineCells(thermo)
            exerciseSimulationCell()
            exerciseStorageCell()
        } finally {
            thermo.close()
            thermoContext.close()
        }
    }

    private object NotebookThermoContract : DeviceContractBuilder() {
        val temperature by mutableDoubleProperty()
        val setpoint by mutableDoubleProperty()
        val reset by action(MetaConverter.meta, MetaConverter.meta)
    }

    private suspend fun exerciseHubCell() {
        val hubContext = krigNotebookContext("hub-demo")
        val hub = MutableDeviceHub("hub".asName(), hubContext)
        val child = device("child", hubContext) {
            mutableProperty("ready", initial = true)
        }

        try {
            hub.attach("child".asName(), child)
            assertTrue("child".asName() in hub.children.keys)
        } finally {
            hub.close()
            hubContext.close()
        }
    }

    private fun exerciseTimelineCells(device: Device) {
        val timeline = device.timeline()
        val replayLog = device.replayLog()
        val replay = replayLog.replay(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
        assertTrue(timeline.events !== replay)
    }

    private suspend fun exerciseSimulationCell() {
        val scheduler = DeterministicScheduler()
        val scope = CoroutineScope(scheduler.asDispatcher())
        try {
            scope.process("ramp") {
                hold(1.seconds)
                hold(2.seconds)
            }
            scheduler.advanceBy(5.seconds)
            assertEquals(5_000L, scheduler.currentTimeMs)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun exerciseStorageCell() {
        val storage = InMemoryEventJournal()
        val history = storage.propertyHistory(
            "thermo".asName(),
            "temperature".asName(),
            MetaConverter.double,
        )

        storage.write(
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(42),
                property = "temperature".asName(),
                value = metaOf(42.0),
                sourceDevice = "thermo".asName(),
            )
        )

        assertEquals(42.0, history.flowHistory().toList().single().value)
    }
}
