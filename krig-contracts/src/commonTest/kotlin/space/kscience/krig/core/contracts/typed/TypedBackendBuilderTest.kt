@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.meta.DeviceSpecBuilder
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TypedBackendBuilderTest {
    private object Spec : DeviceSpecBuilder<Device>() {
        val value by mutableDoubleProperty(
            read = { 0.0 },
            write = { },
        )
        val load by doubleProperty { 0.0 }
        val command by action(MetaConverter.string, MetaConverter.string) { input ->
            input
        }
    }

    @Test
    fun typedBackendExposesReadersWritersSamplersAndActionsWithoutUserCasts() = runTest {
        var value = 0.0
        val sampler = doubleSampler(capacity = 8)
        val backend = typedBackend {
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
    fun typedBackendProvidesMetaControlPlaneBoundary() = runTest {
        var value = 0.0
        val backend = typedBackend {
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
        val backend = typedBackend {
            writer(Spec.value) { }
        }
        val device = stubDevice()

        val outcome = context(device) {
            backend.write(Spec.value.descriptor, metaOf("bad"))
        }

        assertTrue(outcome is DeviceOutcome.Fail, "expected Fail, got $outcome")
        assertTrue(outcome.fault is ValidationFault, "expected ValidationFault, got ${outcome.fault}")
    }

    @Test
    fun typedBackendCanExposeProtocolNeutralBatchReads() = runTest {
        val backend = typedBackend {
            batchReader { descriptors ->
                descriptors.associate { descriptor ->
                    descriptor.name to DeviceOutcome.Ok(metaOf(42.0))
                }
            }
        } as BatchTypedDeviceBackend
        val device = stubDevice()

        val result = context(device) {
            backend.readBatch(listOf(Spec.value.descriptor, Spec.load.descriptor))
        }

        assertEquals(2, result.size)
        assertEquals(42.0, result.getValue(Spec.value.name).getOrThrow().doubleValue)
    }

    private fun stubDevice(): Device = object : AbstractDevice(
        "typed-backend-stub".asName(),
        DeviceRuntime(Context("typed-backend-builder-test-${Random.nextInt()}")),
    ) {
        override suspend fun readProperty(propertyName: Name): Meta = Meta.EMPTY
        override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
        override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
    }
}
