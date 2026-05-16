@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.contracts.typed.typedBackend
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackendDeviceTypedBackendTest {
    private val valueName: Name = "value".asName()

    private val valueSpec = object : MutableDevicePropertySpec<BackendDevice, Double> {
        override val name: Name = valueName
        override val converter: MetaConverter<Double> = MetaConverter.double
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)

        override suspend fun read(device: BackendDevice): Double = error("not used")
        override suspend fun write(device: BackendDevice, value: Double): Unit = error("not used")
    }

    @Test
    fun backendDeviceUsesTypedBackendBeforeMetaFallback() = runTest {
        val backend = NativeTypedBackend()
        val device = BackendDevice(
            backend = backend,
            name = "typed-device".asName(),
            context = Context("typed-backend-test"),
            descriptorSource = DescriptorSource.Empty,
        )

        assertEquals(7.0, device.reader(valueSpec).read())
        device.writer(valueSpec).write(11.0)

        assertEquals(0, backend.metaReads)
        assertEquals(0, backend.metaWrites)
        assertEquals(11.0, backend.lastWritten)
    }

    @Test
    fun backendDeviceUsesTypedBackendSpecForMetaBoundaryValidation() = runTest {
        val backend = typedBackend {
            reader(valueSpec) { 0.0 }
            writer(valueSpec) { }
        }
        val device = BackendDevice(
            backend = backend,
            name = "typed-device-validation".asName(),
            context = Context("typed-backend-validation-test"),
            descriptorSource = DescriptorSource.of(mapOf(valueSpec.name to valueSpec.descriptor)),
        )

        val outcome = device.writePropertyOutcome(valueSpec.name, metaOf("bad"))

        val failure = assertIs<DeviceOutcome.Fail>(outcome)
        assertIs<ValidationFault>(failure.fault)
    }

    private inner class NativeTypedBackend : DeviceBackend, TypedBackend {
        var metaReads: Int = 0
            private set
        var metaWrites: Int = 0
            private set
        var lastWritten: Double? = null
            private set

        @Suppress("UNCHECKED_CAST")
        override fun <T> reader(spec: space.kscience.krig.core.meta.DevicePropertySpec<*, T>): TypedReader<T>? =
            if (spec.name == valueName) GenericTypedReader { 7.0 } as TypedReader<T> else null

        @Suppress("UNCHECKED_CAST")
        override fun <T> writer(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T>? =
            if (spec.name == valueName) {
                GenericTypedWriter<Double> { value -> lastWritten = value } as TypedWriter<T>
            } else {
                null
            }

        context(device: DeviceEnvironment)
        override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> {
            metaReads += 1
            return DeviceOutcome.Ok(metaOf(-1.0))
        }

        context(device: DeviceEnvironment)
        override suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> {
            metaWrites += 1
            return okUnit()
        }

        context(device: DeviceEnvironment)
        override suspend fun execute(
            action: space.kscience.krig.api.descriptors.ActionDescriptor,
            argument: Meta?,
        ): DeviceOutcome<Meta?> = DeviceOutcome.Ok(null)

        override fun close() = Unit
    }
}
