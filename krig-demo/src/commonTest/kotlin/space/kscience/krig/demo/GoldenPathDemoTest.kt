package space.kscience.krig.demo

import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
