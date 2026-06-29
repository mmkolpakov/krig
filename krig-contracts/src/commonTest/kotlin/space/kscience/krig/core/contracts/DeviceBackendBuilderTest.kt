@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.io.toByteArray
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.typed.readObservedOutcome
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Minimal stub [Device] purely to satisfy the `context(device)` contract on backend calls. */
private fun stubDevice(): Device = object : AbstractTestDevice(
    "stub".asName(),
    testRuntime("backend-builder-test"),
) {}

private object BackendSpec : DeviceContractBuilder() {
    val value by property(MetaConverter.double, TypeIds.DOUBLE)
}

/**
 * Tests for [deviceBackend] DSL -- the clean DX path for declaring
 * a [DeviceBackend] without subclassing or hand-written read/write/execute
 * dispatch.
 *
 * Each test below demonstrates a complete connection in 5-15 lines, replacing
 * the ~60-line inheritance form. The DSL is the recommended authoring surface
 * for new connections; the inheritance form remains supported for protocol
 * adapters that already maintain custom byte-level dispatch.
 */
class DeviceBackendBuilderTest {

    private fun pvDescriptor() = PropertyDescriptor(
        "processVariable".asName(),
        PropertyKind.PHYSICAL,
        TypeIds.DOUBLE,
    )

    private fun inputDescriptor() = PropertyDescriptor(
        "input".asName(),
        PropertyKind.LOGICAL,
        TypeIds.DOUBLE,
    )

    @Test
    fun firstOrderPlantInTwelveLines() = runTest {
        // The entire connection: 8 lines of physics + DSL.
        val gain = 1.0
        val tau = 0.5
        val plant = steppedBackend {
            val pv = readable("processVariable", initial = 0.0, converter = MetaConverter.double)
            val input = writable("input", initial = 0.0, converter = MetaConverter.double)
            onStep { dt ->
                val steady = gain * input.value
                pv.value = steady + (pv.value - steady) * exp(-dt.toDouble(DurationUnit.SECONDS) / tau)
            }
        }
        val device = stubDevice()
        context(device) { plant.write(inputDescriptor(), metaOf(10.0)) }.getOrThrow()
        repeat(500) { plant.step(10.milliseconds) }

        val finalPv = context(device) { plant.read(pvDescriptor()) }.getOrThrow().doubleValue ?: 0.0
        assertTrue(abs(finalPv - 10.0) < 0.01, "PV should converge to steady state, got $finalPv")
    }

    @Test
    fun statelessTransducerHasNoStepMethod() = runTest {
        // No onStep block — the builder returns a plain DeviceBackend, not SteppedBackend.
        val transducer = deviceBackend {
            val a = writable("a", initial = 0.0, converter = MetaConverter.double)
            val b = writable("b", initial = 0.0, converter = MetaConverter.double)
            computed("sum") { a.value + b.value }
        }
        val device = stubDevice()
        context(device) {
            transducer.write(PropertyDescriptor("a".asName(), PropertyKind.LOGICAL, TypeIds.DOUBLE), metaOf(3.0)).getOrThrow()
            transducer.write(PropertyDescriptor("b".asName(), PropertyKind.LOGICAL, TypeIds.DOUBLE), metaOf(4.0)).getOrThrow()
        }
        val sum = context(device) {
            transducer.read(PropertyDescriptor("sum".asName(), PropertyKind.PHYSICAL, TypeIds.DOUBLE))
        }.getOrThrow().doubleValue ?: 0.0
        assertEquals(7.0, sum, "Computed property should reflect current cell values")
        // The builder returns DeviceBackend, not SteppedBackend, when `onStep` is omitted.
        assertTrue(transducer !is SteppedBackend)
    }

    @Test
    fun unknownPropertyReturnsFailureWithHelpfulMessage() = runTest {
        val backend = deviceBackend {
            readable("known", initial = 1.0, converter = MetaConverter.double)
        }
        val device = stubDevice()
        val outcome = context(device) {
            backend.read(PropertyDescriptor("unknown".asName(), PropertyKind.LOGICAL, TypeIds.DOUBLE))
        }
        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
        val fault = outcome.fault as GenericOperationFault
        assertTrue(
            fault.message.contains("Unknown property"),
            "Fault message should mention 'Unknown property': ${fault.message}",
        )
    }

    @Test
    fun writingToReadOnlyPropertyReturnsFailure() = runTest {
        val backend = deviceBackend {
            readable("readonly", initial = 1.0, converter = MetaConverter.double)
        }
        val device = stubDevice()
        val outcome = context(device) {
            backend.write(
                PropertyDescriptor("readonly".asName(), PropertyKind.LOGICAL, TypeIds.DOUBLE),
                metaOf(2.0),
            )
        }
        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
    }

    @Test
    fun invalidWritePayloadReturnsValidationFault() = runTest {
        val backend = deviceBackend {
            writable("input", initial = 0.0, converter = MetaConverter.double)
        }
        val device = stubDevice()

        val outcome = context(device) {
            backend.write(
                PropertyDescriptor("input".asName(), PropertyKind.LOGICAL, TypeIds.DOUBLE),
                Meta { "bad" put "payload" },
            )
        }

        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
        assertTrue(outcome.fault is ValidationFault, "expected ValidationFault, got ${outcome.fault}")
    }

    @Test
    fun actionExecutesAndReturnsResult() = runTest {
        val backend = deviceBackend {
            val counter = writable("counter", initial = 0, converter = MetaConverter.int)
            action("increment") {
                counter.value += 1
                metaOf(counter.value)
            }
        }
        val device = stubDevice()
        val result = context(device) {
            backend.execute(ActionDescriptor("increment".asName()), null)
        }.getOrThrow()
        assertEquals(1, result?.intValue)
    }

    @Test
    fun closeCallbackRunsOnClose() = runTest {
        var closed = false
        val device = deviceBackend {
            readable("v", initial = 0.0, converter = MetaConverter.double)
            onClose { closed = true }
        }
        device.close()
        assertTrue(closed, "onClose callback should fire on close()")
    }

    @Test
    fun observedReaderPreservesQuality() = runTest {
        val time = Instant.fromEpochMilliseconds(5)
        val quality = DataQuality(QualitySeverity.UNCERTAIN)
        val backend = deviceBackend {
            observedReader(BackendSpec.value) { ObservedValue(12.5, time, quality) }
        }
        val device = stubDevice()

        val observed = context(device) {
            backend.readObserved(BackendSpec.value.descriptor).getOrThrow()
        }
        val meta = context(device) {
            backend.read(BackendSpec.value.descriptor).getOrThrow()
        }
        val typedObserved = backend.readObservedOutcome(BackendSpec.value).getOrThrow()

        assertEquals(12.5, observed.value?.doubleValue)
        assertEquals(time, observed.time)
        assertEquals(quality, observed.quality)
        assertEquals(12.5, meta.doubleValue)
        assertEquals(12.5, typedObserved.value)
        assertEquals(time, typedObserved.time)
        assertEquals(quality, typedObserved.quality)
    }

    @Test
    fun binaryReaderUsesBinaryPath() = runTest {
        val payload = byteArrayOf(9, 8, 7)
        val backend = deviceBackend {
            bytesReader(BackendSpec.value) { payload }
        }
        val device = stubDevice()

        val binary = context(device) {
            backend.readBinaryOutcome(BackendSpec.value.descriptor).getOrThrow().toByteArray()
        }

        assertContentEquals(payload, binary)
    }
}
