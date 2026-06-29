@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class MetaBackendAdapterTest {

    private object Spec : DeviceContractBuilder() {
        val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        val command by action(MetaConverter.string, MetaConverter.string)
    }

    @Suppress("UNCHECKED_CAST")
    private fun metaFreeBackend(cellRef: DoubleArray): TypedBackend = object : TypedBackend {
        override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? =
            if (spec.name == Spec.value.name) TypedReader { cellRef[0] as T } else null

        override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? =
            if (spec.name == Spec.value.name) {
                TypedObservedReader {
                    ObservedValue(cellRef[0] as T, OBSERVED_TIME, DataQuality(QualitySeverity.UNCERTAIN))
                }
            } else {
                null
            }

        override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? =
            if (spec.name == Spec.value.name) TypedWriter { next -> cellRef[0] = next as Double } else null

        override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? =
            if (spec.name == Spec.command.name) TypedAction { input -> "ack:$input" as O } else null
    }

    @Test
    fun metaPlaneIsSynthesizedFromTypedHandles() = runTest {
        val cell = doubleArrayOf(0.0)
        val backend = metaBackendOf(
            typed = metaFreeBackend(cell),
            propertySpecs = mapOf(Spec.value.name to Spec.value),
            actionSpecs = mapOf(Spec.command.name to Spec.command),
        )
        val device = stubDevice()

        assertIs<TypedDeviceBackend>(backend)
        assertEquals(Spec.value, backend.propertySpec(Spec.value.name))
        assertEquals(Spec.command, backend.actionSpec(Spec.command.name))
        assertEquals(0.0, backend.reader(Spec.value)?.read())

        context(device) {
            backend.write(Spec.value.descriptor, metaOf(7.5)).getOrThrow()
            assertEquals(7.5, backend.reader(Spec.value)?.read())
            val read = backend.read(Spec.value.descriptor).getOrThrow()
            assertEquals(7.5, read.doubleValue)
            val result = backend.execute(Spec.command.descriptor, metaOf("run")).getOrThrow()
            assertEquals("ack:run", result?.stringValue)
        }
    }

    @Test
    fun metaObservedReadPreservesTypedObservedQuality() = runTest {
        val backend = metaBackendOf(
            typed = metaFreeBackend(doubleArrayOf(12.25)),
            propertySpecs = mapOf(Spec.value.name to Spec.value),
        )
        val device = stubDevice()

        val observed = context(device) { backend.readObserved(Spec.value.descriptor) }.getOrThrow()

        assertEquals(12.25, observed.value?.doubleValue)
        assertEquals(OBSERVED_TIME, observed.time)
        assertEquals(DataQuality(QualitySeverity.UNCERTAIN), observed.quality)
    }

    @Test
    fun descriptorMismatchFailsWithoutThrowing() = runTest {
        val backend = metaBackendOf(
            typed = metaFreeBackend(doubleArrayOf(0.0)),
            propertySpecs = mapOf(Spec.value.name to Spec.value),
        )
        val device = stubDevice()
        val mismatched = PropertyDescriptor(
            name = Spec.value.name,
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.STRING,
        )

        val outcome = context(device) { backend.read(mismatched) }

        val failure = assertIs<OperationOutcome.Fail>(outcome)
        assertIs<ValidationFault>(failure.fault)
    }

    @Test
    fun typedBackendFastPathMatchesMetaBoundary() = runTest {
        var cell = 1.0
        val backend = deviceBackend {
            reader(Spec.value) { cell }
            writer(Spec.value) { next -> cell = next }
            action(Spec.command) { input -> "ack:$input" }
        }
        val device = stubDevice()

        assertEquals(1.0, backend.reader(Spec.value)?.read())
        assertEquals(Spec.value, backend.propertySpec(Spec.value.name))

        context(device) {
            backend.write(Spec.value.descriptor, metaOf(9.0)).getOrThrow()
            assertEquals(9.0, backend.reader(Spec.value)?.read())
            val metaRead = backend.read(Spec.value.descriptor).getOrThrow()
            assertEquals(9.0, metaRead.doubleValue, "Meta boundary read returned $metaRead")
            val metaAction = backend.execute(Spec.command.descriptor, metaOf("sync")).getOrThrow()
            assertEquals("ack:sync", metaAction?.stringValue, "Meta boundary action returned $metaAction")
        }
    }

    @Test
    fun unknownPropertyFailsWithoutThrowing() = runTest {
        val backend = metaBackendOf(metaFreeBackend(doubleArrayOf(0.0)), emptyMap())
        val device = stubDevice()

        val outcome = context(device) { backend.read(Spec.value.descriptor) }
        assertTrue(outcome is OperationOutcome.Fail, "expected Fail, got $outcome")
    }

    private fun stubDevice(): Device = object : AbstractDevice(
        "meta-adapter-stub".asName(),
        DeviceRuntime(Context("meta-adapter-test-${Random.nextInt()}")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(Meta.EMPTY)

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
    }

    private companion object {
        val OBSERVED_TIME: Instant = Instant.fromEpochMilliseconds(123_456)
    }
}
