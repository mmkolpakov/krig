package space.kscience.krig.demo

import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.messages.DeviceMessageType
import space.kscience.krig.api.messages.KrigWireFormats
import space.kscience.krig.api.messages.KrigWireTopics
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.tasks.DeviceTaskPhase
import space.kscience.krig.flow.FlowMessageType
import space.kscience.krig.flow.FlowQualities
import space.kscience.krig.core.contracts.RemoteTypedActivationStatus
import space.kscience.krig.core.contracts.activateTypedFacade
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.contracts.remoteManifestRef
import space.kscience.krig.dsl.device
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GoldenPathDemoTest {

    @Test
    fun typedReadWriteAndQuality() = runTest {
        val ctx = demoContext("golden-test")
        val sensor = device("sensor", goldenBackend(), ctx) { manifest(GoldenManifest) }

        sensor.write(GoldenSpec.rpm, 1_500.0)
        assertEquals(1_500.0, sensor.read(GoldenSpec.rpm))

        val batch = sensor.readBatchOutcome(listOf(GoldenSpec.rpm.name, GoldenSpec.temperature.name))
        val temperature = batch.getValue(GoldenSpec.temperature.name)
        assertTrue(temperature is OperationOutcome.Ok)
        // 60 + 1500/100 = 75 °C → over-temp → UNCERTAIN.
        assertEquals(QualitySeverity.UNCERTAIN, temperature.value.quality.severity)

        val sampler = sensor.doubleSampler(GoldenSpec.rpm)
        assertNotNull(sampler)
        assertEquals(1_500.0, sampler.snapshotDoubleArray().last())

        sensor.close()
        ctx.close()
    }

    @Test
    fun retryRecoversTransientFailures() = runTest {
        val ctx = demoContext("flaky-test")
        val driver = FlakyPump(failuresBeforeSuccess = 2)
        val pump = device("flakyPump", driver.backend(), ctx) {
            manifest(PumpManifest)
            install(DemoRetry) {
                policy = RetryPolicy(maxAttempts = 2, initialDelay = 1.milliseconds)
            }
        }

        assertEquals(1_200.0, pump.read(PumpSpec.rpm))
        assertEquals(3, driver.attempts)

        pump.close()
        ctx.close()
    }

    @Test
    fun tagTableBackendProjectsTypedAndRawPaths() = runTest {
        val snapshot = tagTableBackendSnapshot()

        assertEquals(2, snapshot.manifestProperties)
        assertEquals(1_420.0, snapshot.typedRpm)
        assertEquals("auto", snapshot.rawMode)
        assertEquals(QualitySeverity.UNCERTAIN, snapshot.rpmQuality)
    }

    @Test
    fun labDiscoveryKeepsAdHocMetaPathExplicit() = runTest {
        val snapshot = labDiscoverySnapshot()

        assertFalse(snapshot.strictWriteAccepted)
        assertTrue(snapshot.adHocPropertyVisible)
        assertEquals(2.5, snapshot.discoveredGain)
    }

    @Test
    fun distributedTypedActivationUsesSchemaHash() {
        val announcement = PumpManifest.remoteManifestRef(deviceId = "edge.lineA.pump".parseAsName())
        val accepted = PumpManifest.activateTypedFacade(announcement)
        val rejected = PumpManifest.activateTypedFacade(announcement.copy(schemaHash = "fnv1a64:0000000000000000"))

        assertTrue(accepted.typedFacadeEnabled)
        assertFalse(rejected.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.SchemaMismatch, rejected.status)
        assertTrue(rejected.reason.contains("schema mismatch"))
    }

    @Test
    fun edgeTelemetryWireChunkStaysCommonAndQualityAware() {
        val chunk = edgeTelemetryWireChunk(sampleCount = 16)

        assertEquals(16, chunk.rowCount)
        assertEquals(QualitySeverity.UNCERTAIN, chunk.qualityAt(row = 0, seriesIndex = 0).severity)
        assertEquals(QualitySeverity.GOOD, chunk.qualityAt(row = 1, seriesIndex = 0).severity)
    }

    @Test
    fun magixEnvelopeCarriesKrigFrameConstants() {
        val snapshot = magixEnvelopeSnapshot()

        assertEquals(KrigWireFormats.MagixEnvelope, snapshot.outerFormat)
        assertEquals(KrigWireFormats.DeviceMessageFrame, snapshot.innerFormat)
        assertEquals(KrigWireTopics.deviceMessages("edge.lineA.pump".parseAsName()), snapshot.topic)
        assertEquals(DeviceMessageType.PropertyChanged, snapshot.messageType)
        assertNotNull(snapshot.schemaHeader)
    }

    @Test
    fun distributedFlowTransferUsesFlowMessageContributor() {
        val snapshot = distributedFlowTransferSnapshot()

        assertEquals(KrigWireFormats.MagixEnvelope, snapshot.outerFormat)
        assertEquals(KrigWireFormats.DeviceMessageFrame, snapshot.innerFormat)
        assertEquals(KrigWireTopics.deviceMessages("edge.lineA.flow".parseAsName()), snapshot.topic)
        assertEquals(FlowMessageType.Transfer, snapshot.messageType)
        assertEquals(2.0, snapshot.amount)
        assertEquals(1, snapshot.sequence)
        assertEquals(FlowQualities.DelayedMaterial.code?.id, snapshot.delayedQualityCode)
    }

    @Test
    fun calibrationTaskIsActionTriggeredObservableState() = runTest {
        val snapshot = calibrationTaskDemoSnapshot()

        assertEquals("calibration-1", snapshot.taskId.value)
        assertEquals(DeviceTaskPhase.Running, snapshot.taskPhase)
        assertEquals(0.5, snapshot.progressFraction)
        assertEquals("taskState".asName(), snapshot.taskStateProperty)
        assertEquals(DeviceMessageType.TaskStateChanged, snapshot.messageType)
    }
}
