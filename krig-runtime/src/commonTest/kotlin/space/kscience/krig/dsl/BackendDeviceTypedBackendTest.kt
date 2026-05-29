@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackendDeviceTypedBackendTest {
    private val valueName: Name = "value".asName()

    private val valueSpec = object : MutableDevicePropertyContract<Double> {
        override val name: Name = valueName
        override val converter: MetaConverter<Double> = MetaConverter.double
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
    }

    private object TestAuthorizationService : PluginFactory<AuthorizationService> {
        override val tag: PluginTag get() = AuthorizationService.tag

        override fun build(context: Context, meta: Meta): AuthorizationService =
            object : AbstractPlugin(meta), AuthorizationService {
                override suspend fun checkPermission(principal: Principal, permission: Permission) = Unit
            }
    }

    private fun permissiveContext(): Context = Context("loose-meta-test") {
        plugin(TestAuthorizationService)
        plugin(AuditService)
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
    fun backendDeviceUsesBackendSpecForMetaBoundaryValidation() = runTest {
        val backend = backend {
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

        val failure = assertIs<OperationOutcome.Fail>(outcome)
        assertIs<ValidationFault>(failure.fault)
    }

    @Test
    fun backendDeviceDelegatesBatchBinaryAndWriteBatch() = runTest {
        val payload = byteArrayOf(13, 0)
        var binaryRequests: List<Name> = emptyList()
        var writeRequests: Map<Name, Meta> = emptyMap()
        val backend = backend {
            batchBinaryReader { descriptors ->
                binaryRequests = descriptors.map { it.name }
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(payload.asBinary())
                }
            }
            batchWriter { values ->
                writeRequests = values.mapKeys { (descriptor, _) -> descriptor.name }
                values.keys.associate { descriptor ->
                    descriptor.name to okUnit()
                }
            }
        }
        val device = BackendDevice(
            backend = backend,
            name = "typed-device-batch".asName(),
            context = Context("typed-backend-batch-test"),
            descriptorSource = DescriptorSource.of(mapOf(valueSpec.name to valueSpec.descriptor)),
        )

        val binaryOutcome = device.readBatchBinaryOutcome(listOf(valueSpec.name)).getValue(valueSpec.name)
        val binaryOk = assertIs<OperationOutcome.Ok<space.kscience.dataforge.io.Binary>>(binaryOutcome)
        assertEquals(payload.toList(), binaryOk.value.toByteArray().toList())
        assertEquals(listOf(valueSpec.name), binaryRequests)

        val writeOutcome = device.writeBatchOutcome(mapOf(valueSpec.name to metaOf(9.0))).getValue(valueSpec.name)
        assertIs<OperationOutcome.Ok<Unit>>(writeOutcome)
        assertEquals(9.0, writeRequests.getValue(valueSpec.name).doubleValue)
    }

    @Test
    fun backendDeviceUsesTypedBackendSpecsForBatchWithoutDescriptorSource() = runTest {
        var batchRequests: List<Name> = emptyList()
        val backend = backend {
            reader(valueSpec) { 7.0 }
            batchObservedReader { descriptors ->
                batchRequests = descriptors.map { it.name }
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(
                        ObservedValue(metaOf(17.0), clock.now(), DataQuality.GOOD),
                    )
                }
            }
        }
        val device = BackendDevice(
            backend = backend,
            name = "typed-device-batch-no-manifest".asName(),
            context = Context("typed-backend-batch-no-manifest-test"),
            descriptorSource = DescriptorSource.Empty,
        )

        val outcome = device.readBatchOutcome(listOf(valueSpec.name)).getValue(valueSpec.name)
        val observed = assertIs<OperationOutcome.Ok<ObservedValue<Meta?>>>(outcome)

        assertEquals(17.0, observed.value.value?.doubleValue)
        assertEquals(listOf(valueSpec.name), batchRequests)
    }

    @Test
    fun backendOutcomeCancellationDoesNotMarkDeviceFailed() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val backend = object : DeviceBackend {
            context(device: DeviceEnvironment)
            override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
                started.complete(Unit)
                never.await()
                return OperationOutcome.Ok(Meta.EMPTY)
            }

            context(device: DeviceEnvironment)
            override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> =
                okUnit()

            context(device: DeviceEnvironment)
            override suspend fun execute(
                action: space.kscience.krig.api.descriptors.ActionDescriptor,
                argument: Meta?,
            ): OperationOutcome<Meta?> = OperationOutcome.Ok(null)

            override fun close() = Unit
        }
        val device = BackendDevice(
            backend = backend,
            name = "typed-device-cancel".asName(),
            context = Context("typed-backend-cancel-test"),
            descriptorSource = DescriptorSource.of(mapOf(valueSpec.name to valueSpec.descriptor)),
        )

        val job = launch {
            val outcome = device.readPropertyOutcome(valueSpec.name)
            error("read completed unexpectedly: $outcome")
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(device.lifecycleState !is LifecycleState.Failed)
    }

    @Test
    fun backendDeviceRejectsUndeclaredMetaPropertiesByDefault() = runTest {
        val device = BackendDevice(
            backend = LooseMetaBackend(),
            name = "strict-meta".asName(),
            context = Context("strict-meta-test"),
            descriptorSource = DescriptorSource.Empty,
        )

        val outcome = device.readPropertyOutcome("loose".asName())

        val failure = assertIs<OperationOutcome.Fail>(outcome)
        assertEquals(OperationFaultTypes.UnknownProperty, failure.fault.faultType)
    }

    @Test
    fun deviceBuilderAllowsAdHocPropertiesOnlyWhenEnabled() = runTest {
        val backend = LooseMetaBackend()
        val device = device("loose-meta", backend, permissiveContext()) {
            allowAdHocProperties = true
        }

        assertEquals(7.0, device.readProperty("loose".asName()).doubleValue)
        device.writeProperty("loose".asName(), metaOf(9.0))

        assertEquals("loose".asName(), backend.lastWrite)
        assertEquals(9.0, backend.lastValue?.doubleValue)
    }

    private inner class NativeTypedBackend : DeviceBackend, TypedBackend {
        var metaReads: Int = 0
            private set
        var metaWrites: Int = 0
            private set
        var lastWritten: Double? = null
            private set

        @Suppress("UNCHECKED_CAST")
        override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? =
            if (spec.name == valueName) GenericTypedReader { 7.0 } as TypedReader<T> else null

        @Suppress("UNCHECKED_CAST")
        override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? =
            if (spec.name == valueName) {
                GenericTypedWriter<Double> { value -> lastWritten = value } as TypedWriter<T>
            } else {
                null
            }

        context(device: DeviceEnvironment)
        override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
            metaReads += 1
            return OperationOutcome.Ok(metaOf(-1.0))
        }

        context(device: DeviceEnvironment)
        override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
            metaWrites += 1
            return okUnit()
        }

        context(device: DeviceEnvironment)
        override suspend fun execute(
            action: space.kscience.krig.api.descriptors.ActionDescriptor,
            argument: Meta?,
        ): OperationOutcome<Meta?> = OperationOutcome.Ok(null)

        override fun close() = Unit
    }

    private class LooseMetaBackend : DeviceBackend {
        var lastWrite: Name? = null
            private set
        var lastValue: Meta? = null
            private set

        context(device: DeviceEnvironment)
        override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
            OperationOutcome.Ok(metaOf(7.0))

        context(device: DeviceEnvironment)
        override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
            lastWrite = property.name
            lastValue = value
            return okUnit()
        }

        context(device: DeviceEnvironment)
        override suspend fun execute(
            action: space.kscience.krig.api.descriptors.ActionDescriptor,
            argument: Meta?,
        ): OperationOutcome<Meta?> = OperationOutcome.Ok(null)

        override fun close() = Unit
    }
}
