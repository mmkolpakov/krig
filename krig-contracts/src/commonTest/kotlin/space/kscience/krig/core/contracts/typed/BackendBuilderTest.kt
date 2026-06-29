@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readBinaryOutcome
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class BackendBuilderTest {
    private object Spec : DeviceContractBuilder() {
        val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        val load by property(MetaConverter.double, TypeIds.DOUBLE)
        val command by action(MetaConverter.string, MetaConverter.string)
    }

    @Test
    fun backendExposesReadersWritersSamplersAndActionsWithoutUserCasts() = runTest {
        var value = 0.0
        val sampler = doubleSampler(capacity = 8)
        val backend = deviceBackend {
            reader(Spec.value) { value }
            writer(Spec.value) { next ->
                value = next
                sampler.publishDouble(next)
            }
            sampler(Spec.value) { sampler }
            reader(Spec.load) { value / 2.0 }
            action(Spec.command) { command -> "ack:$command" }
        }

        backend.writer(Spec.value)?.write(4.0)
        assertEquals(4.0, backend.reader(Spec.value)?.read())
        assertEquals(2.0, backend.reader(Spec.load)?.read())
        assertSame(sampler, backend.sampler(Spec.value))
        assertEquals("ack:go", backend.action(Spec.command)?.execute("go"))
    }

    @Test
    fun backendProvidesMetaControlPlaneBoundary() = runTest {
        var value = 0.0
        val backend = deviceBackend {
            reader(Spec.value) { value }
            writer(Spec.value) { next -> value = next }
            action(Spec.command) { command -> "ack:$command" }
        }
        val device = stubDevice()

        context(device) {
            backend.write(Spec.value.descriptor, metaOf(7.5)).getOrThrow()
            val readMeta = backend.read(Spec.value.descriptor).getOrThrow()
            assertEquals(7.5, readMeta.doubleValue)
            val result = backend.execute(Spec.command.descriptor, metaOf("run")).getOrThrow()
            assertEquals("ack:run", result?.stringValue)
        }
    }

    @Test
    fun invalidMetaWriteReturnsValidationFault() = runTest {
        val backend = deviceBackend {
            writer(Spec.value) { }
        }
        val device = stubDevice()

        val outcome = context(device) {
            backend.write(Spec.value.descriptor, metaOf("bad"))
        }

        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
        assertTrue(outcome.fault is ValidationFault, "expected ValidationFault, got ${outcome.fault}")
    }

    @Test
    fun backendCanExposeProtocolNeutralBatchReads() = runTest {
        val backend = deviceBackend {
            batchMetaReader { descriptors ->
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(metaOf(42.0))
                }
            }
        }
        val device = stubDevice()

        val observed = context(device) {
            backend.readBatchObserved(listOf(Spec.value.descriptor, Spec.load.descriptor))
        }

        assertEquals(2, observed.size)
        assertEquals(42.0, observed.getValue(Spec.value.name).getOrThrow().value?.doubleValue)
        assertEquals(DataQuality.GOOD, observed.getValue(Spec.value.name).getOrThrow().quality)
    }

    @Test
    fun observedReaderPreservesQualityAcrossMetaBoundary() = runTest {
        val time = Instant.fromEpochMilliseconds(42)
        val quality = DataQuality(QualitySeverity.UNCERTAIN)
        val backend = deviceBackend {
            observedReader(Spec.value) { ObservedValue(7.5, time, quality) }
        }
        val device = stubDevice()

        val observed = context(device) {
            backend.readObserved(Spec.value.descriptor).getOrThrow()
        }
        val typedObserved = backend.readObservedOutcome(Spec.value).getOrThrow()

        assertEquals(7.5, observed.value?.doubleValue)
        assertEquals(time, observed.time)
        assertEquals(quality, observed.quality)
        assertEquals(7.5, typedObserved.value)
        assertEquals(time, typedObserved.time)
        assertEquals(quality, typedObserved.quality)
        assertEquals(7.5, backend.reader(Spec.value)?.read())
    }

    @Test
    fun batchObservedReaderPreservesPerPropertyQuality() = runTest {
        val quality = DataQuality(QualitySeverity.UNCERTAIN)
        val backend = deviceBackend {
            batchObservedReader { descriptors ->
                val time = clock.now()
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(ObservedValue(metaOf(42.0), time, quality))
                }
            }
        }
        val device = stubDevice()

        val result = context(device) {
            backend.readBatchObserved(listOf(Spec.value.descriptor, Spec.load.descriptor))
        }

        val observed = result.getValue(Spec.value.name).getOrThrow()
        assertEquals(42.0, observed.value?.doubleValue)
        assertEquals(quality, observed.quality)
    }

    @Test
    fun batchBinaryReaderKeepsPayloadOffMetaPath() = runTest {
        val payload = byteArrayOf(5, 6, 7)
        val backend = deviceBackend {
            batchBinaryReader { descriptors ->
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(payload.asBinary())
                }
            }
        }
        val device = stubDevice()

        val result = context(device) {
            backend.readBatchBinary(listOf(Spec.value.descriptor, Spec.load.descriptor))
        }

        assertContentEquals(payload, result.getValue(Spec.value.name).getOrThrow().toByteArray())
    }

    @Test
    fun batchWriterUsesSingleBackendBody() = runTest {
        var received = emptyMap<Name, Meta>()
        val backend = deviceBackend {
            batchWriter { values ->
                received = values.mapKeys { (descriptor, _) -> descriptor.name }
                values.entries.associate { (descriptor, _) ->
                    descriptor.name to OperationOutcome.OkUnit
                }
            }
        }
        val device = stubDevice()
        val payload = metaOf(9.0)

        val result = context(device) {
            backend.writeBatch(mapOf(Spec.value.descriptor to payload))
        }

        assertSame(payload, received.getValue(Spec.value.name))
        assertEquals(OperationOutcome.OkUnit, result.getValue(Spec.value.name))
    }

    @Test
    fun binaryReaderKeepsBytesOffMetaPath() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        val backend = deviceBackend {
            bytesReader(Spec.load) { payload }
        }
        val device = stubDevice()

        val binary = context(device) {
            backend.readBinaryOutcome(Spec.load.descriptor).getOrThrow().toByteArray()
        }

        assertContentEquals(payload, binary)
    }

    @Test
    fun binaryReadFailsWhenNoBinaryReaderExists() = runTest {
        val backend = deviceBackend {
            reader(Spec.load) { 12.5 }
        }
        val device = stubDevice()

        val outcome = context(device) {
            backend.readBinaryOutcome(Spec.load.descriptor)
        }

        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
    }

    private fun stubDevice(): Device = object : AbstractDevice(
        "typed-backend-stub".asName(),
        DeviceRuntime(Context("typed-backend-builder-test-${Random.nextInt()}")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(Meta.EMPTY)

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
    }
}
