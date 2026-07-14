@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
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
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.ConnectionProperty
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.DynamicDiscoveryPolicy
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.meta.mutableDevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class BackendDeviceTypedBackendTest {
    private val valueName: Name = "value".asName()

    private val valueSpec = object : MutableDevicePropertyContract<Double> {
        override val name: Name = valueName
        override val converter: MetaConverter<Double> = MetaConverter.double
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
    }

    private fun permissiveContext(name: String): Context = Context(name) {
        plugin(AllowAllAuthorizationService)
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
    fun backendDeviceUsesTypedObservedBackendBeforeMetaFallback() = runTest {
        val backend = NativeTypedBackend()
        val device = BackendDevice(
            backend = backend,
            name = "typed-observed-device".asName(),
            context = Context("typed-observed-backend-test"),
            descriptorSource = DescriptorSource.Empty,
        )

        val observed = device.readObservedOutcome(valueSpec).getOrThrow()

        assertEquals(8.0, observed.value)
        assertEquals(backend.observedTime, observed.time)
        assertEquals(DataQuality.GOOD, observed.quality)
        assertEquals(0, backend.metaReads)
    }

    @Test
    fun backendDeviceFallsBackToMetaWhenTypedObservedReaderIsAbsent() = runTest {
        val backend = deviceBackend {
            reader(valueSpec) { 3.5 }
        }
        val device = BackendDevice(
            backend = backend,
            name = "typed-observed-fallback-device".asName(),
            context = Context("typed-observed-fallback-test"),
            descriptorSource = DescriptorSource.Empty,
        )

        val observed = device.readObservedOutcome(valueSpec).getOrThrow()

        assertEquals(3.5, observed.value)
        assertEquals(DataQuality.GOOD, observed.quality)
    }

    @Test
    fun backendDeviceUsesBackendSpecForMetaBoundaryValidation() = runTest {
        val backend = deviceBackend {
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
        val backend = deviceBackend {
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
        val backend = deviceBackend {
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
    fun typedWritableCellWorksThroughMetaFallbackWithoutDescriptorSource() = runTest {
        val spec = mutableDevicePropertyContract(
            name = Name.of("setpoint"),
            converter = MetaConverter.double,
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        lateinit var cell: ConnectionProperty<Double>
        val backend = deviceBackend {
            cell = writable(spec, initial = 1.5)
        }
        val context = permissiveContext("typed-cell-meta-fallback-test")
        val device = BackendDevice(
            backend = backend,
            name = Name.of("typed-cell-meta-fallback"),
            context = context,
            descriptorSource = DescriptorSource.Empty,
        )

        assertSame(spec, backend.propertySpec(spec.name))
        assertEquals(spec.descriptor, device.propertyDescriptors[spec.name])
        assertEquals(1.5, device.readProperty(spec.name).doubleValue)

        device.writeProperty(spec.name, metaOf(4.5))
        assertEquals(4.5, cell.value)
        assertEquals(4.5, device.reader(spec).read())

        device.writer(spec).write(6.0)
        assertEquals(6.0, cell.value)

        device.close()
        context.close()
    }

    @Test
    fun backendOutcomeCancellationDoesNotMarkDeviceFailed() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val backend = object : DeviceBackend {
            override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
                val boundEnvironment = environment
                return object : BoundDeviceBackend {
                    override val environment: BackendEnvironment = boundEnvironment

                    override suspend fun read(property: PropertyDescriptor): Meta {
                        started.complete(Unit)
                        never.await()
                        return Meta.EMPTY
                    }

                    override suspend fun write(property: PropertyDescriptor, value: Meta) = Unit

                    override suspend fun execute(
                        action: space.kscience.krig.api.descriptors.ActionDescriptor,
                        argument: Meta?,
                    ): Meta? = null

                    override fun close() = Unit
                }
            }
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
    fun deviceBuilderAllowsSchemaLessPropertiesThroughDynamicPolicy() = runTest {
        val backend = LooseMetaBackend()
        val ctx = permissiveContext("loose-meta-test")
        val device = device("loose-meta", backend, ctx) {
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.AdHoc
        }

        assertEquals(7.0, device.readProperty("loose".asName()).doubleValue)
        device.writeProperty("loose".asName(), metaOf(9.0))

        assertEquals("loose".asName(), backend.lastWrite)
        assertEquals(9.0, backend.lastValue?.doubleValue)

        device.close()
        ctx.close()
    }

    @Test
    fun adHocDynamicPropertiesStayTransient() = runTest {
        val looseName = "loose".asName()
        val device = BackendDevice(
            backend = LooseMetaBackend(),
            name = "ad-hoc-meta".asName(),
            context = Context("ad-hoc-meta-test"),
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.AdHoc,
        )

        assertEquals(7.0, device.readProperty(looseName).doubleValue)

        assertTrue(device.discoveredPropertyDescriptors.isEmpty())
        assertTrue(device.propertyDescriptors.isEmpty())
    }

    @Test
    fun learnDynamicPropertiesExposeOverlayOnly() = runTest {
        val looseName = "loose".asName()
        val device = BackendDevice(
            backend = LooseMetaBackend(),
            name = "learn-meta".asName(),
            context = Context("learn-meta-test"),
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.Learn,
        )

        assertTrue(device.discoveredPropertyDescriptors.isEmpty())
        assertEquals(7.0, device.readProperty(looseName).doubleValue)

        assertEquals(setOf(looseName), device.discoveredPropertyDescriptors.keys)
        assertTrue(device.propertyDescriptors.isEmpty())
    }

    @Test
    fun catalogDynamicPropertiesRequireSeededDescriptor() = runTest {
        val looseName = "loose".asName()
        val looseDescriptor = PropertyDescriptor(
            name = looseName,
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.META,
        )
        val device = BackendDevice(
            backend = LooseMetaBackend(),
            name = "catalog-meta".asName(),
            context = Context("catalog-meta-test"),
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.Catalog,
            initialDiscoveredProperties = mapOf(looseName to looseDescriptor),
        )

        assertEquals(7.0, device.readProperty(looseName).doubleValue)
        assertEquals(setOf(looseName), device.discoveredPropertyDescriptors.keys)
        assertTrue(device.propertyDescriptors.isEmpty())

        val unknown = device.readPropertyOutcome("other".asName())
        val failure = assertIs<OperationOutcome.Fail>(unknown)
        assertEquals(OperationFaultTypes.UnknownProperty, failure.fault.faultType)
    }

    @Test
    fun dynamicOverlaySurvivesPipelineAssembly() = runTest {
        val looseName = "loose".asName()
        val looseDescriptor = PropertyDescriptor(
            name = looseName,
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.META,
        )
        val ctx = permissiveContext("catalog-builder-meta-test")
        val device = device("catalog-builder-meta", LooseMetaBackend(), ctx) {
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.Catalog
            discoveredProperty(looseDescriptor)
        }

        assertEquals(7.0, device.readProperty(looseName).doubleValue)

        val overlay = assertIs<DynamicDescriptorOverlay>(device)
        assertEquals(DynamicDiscoveryPolicy.Catalog, overlay.dynamicDiscoveryPolicy)
        assertEquals(setOf(looseName), overlay.discoveredPropertyDescriptors.keys)
        assertTrue(device.propertyDescriptors.isEmpty())

        device.close()
        ctx.close()
    }

    @Test
    fun declaredReadOnlyPropertiesStayReadOnlyInAdHocMode() = runTest {
        val descriptor = PropertyDescriptor(
            name = valueName,
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        val device = BackendDevice(
            backend = LooseMetaBackend(),
            name = "declared-ad-hoc-meta".asName(),
            context = Context("declared-ad-hoc-meta-test"),
            descriptorSource = DescriptorSource.of(mapOf(valueName to descriptor)),
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.AdHoc,
        )

        assertFalse(device.propertySpec(valueName) is MutableDevicePropertyContract<*>)
        assertTrue(device.propertySpec("loose".asName()) is MutableDevicePropertyContract<*>)
    }

    private inner class NativeTypedBackend : DeviceBackend, TypedBackend {
        var metaReads: Int = 0
            private set
        var metaWrites: Int = 0
            private set
        var lastWritten: Double? = null
            private set
        val observedTime: Instant = Instant.fromEpochMilliseconds(123)

        @Suppress("UNCHECKED_CAST")
        override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? =
            if (spec.name == valueName) TypedReader { 7.0 } as TypedReader<T> else null

        @Suppress("UNCHECKED_CAST")
        override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? =
            if (spec.name == valueName) {
                TypedObservedReader { ObservedValue(8.0, observedTime, DataQuality.GOOD) } as TypedObservedReader<T>
            } else {
                null
            }

        @Suppress("UNCHECKED_CAST")
        override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? =
            if (spec.name == valueName) {
                TypedWriter<Double> { value -> lastWritten = value } as TypedWriter<T>
            } else {
                null
            }

        override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
            val boundEnvironment = environment
            return object : BoundDeviceBackend {
                override val environment: BackendEnvironment = boundEnvironment

                override suspend fun read(property: PropertyDescriptor): Meta {
                    metaReads += 1
                    return metaOf(-1.0)
                }

                override suspend fun write(property: PropertyDescriptor, value: Meta) {
                    metaWrites += 1
                }

                override suspend fun execute(
                    action: space.kscience.krig.api.descriptors.ActionDescriptor,
                    argument: Meta?,
                ): Meta? = null

                override fun close() = Unit
            }
        }
    }

    private class LooseMetaBackend : DeviceBackend {
        var lastWrite: Name? = null
            private set
        var lastValue: Meta? = null
            private set

        override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
            val boundEnvironment = environment
            return object : BoundDeviceBackend {
                override val environment: BackendEnvironment = boundEnvironment

                override suspend fun read(property: PropertyDescriptor): Meta =
                    metaOf(7.0)

                override suspend fun write(property: PropertyDescriptor, value: Meta) {
                    lastWrite = property.name
                    lastValue = value
                }

                override suspend fun execute(
                    action: space.kscience.krig.api.descriptors.ActionDescriptor,
                    argument: Meta?,
                ): Meta? = null

                override fun close() = Unit
            }
        }
    }
}
