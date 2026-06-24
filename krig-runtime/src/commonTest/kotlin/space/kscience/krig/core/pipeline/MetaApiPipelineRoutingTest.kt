@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.pipeline

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val seq: AtomicInt = AtomicInt(0)
private fun freshContext(): Context = Context("meta-api-${seq.addAndFetch(1)}")

/**
 * Proves that `device.readProperty(Name): Meta` and `writeProperty(Name, Meta)` route
 * through the full operation pipeline (gates → executor → observers) when the delegate
 * exposes a registered [DevicePropertyContract] via [Device.propertySpec].
 *
 * This is a live regression test of the production dynamic-`Meta` → typed-executor path.
 */
class MetaApiPipelineRoutingTest {

    private class DoubleCellDevice : AbstractDevice("cell".asName(), DeviceRuntime(freshContext())) {
        private val cell = atomic(0.0)

        val valueSpec = object : MutableDevicePropertyContract<Double> {
            override val name: Name = "value".asName()
            override val descriptor = PropertyDescriptor(
                name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE,
            )
            override val converter: MetaConverter<Double> = MetaConverter.double
        }

        override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
            if (propertyName == valueSpec.name) valueSpec else null

        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            runCatchingOperation {
                check(propertyName == valueSpec.name)
                MetaConverter.double.convert(cell.value)
            }

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            runCatchingOperation {
                check(propertyName == valueSpec.name)
                cell.value = value.double ?: error("expected Double")
            }

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
    }

    @Test
    fun metaReadFiresObserversWhenSpecIsKnown() = runTest {
        val device = DoubleCellDevice()
        val observed = mutableListOf<Pair<Name, OperationFault?>>()
        val builder = PipelineBuilder().apply {
            observeRead { operation, _, fault -> observed += operation.name to fault }
        }
        val wrapped = wrapWithPipeline(device, builder, "cell", device.context, autoInstallDefaults = false)

        wrapped.writeProperty(device.valueSpec.name, MetaConverter.double.convert(42.0))
        val result = wrapped.readProperty(device.valueSpec.name)

        assertEquals(42.0, result.double)
        assertTrue(observed.any { it.first == device.valueSpec.name && it.second == null },
            "observer must see Meta read routed through the typed executor")
    }

    @Test
    fun metaReadOnUnknownNameFailsWithoutSyntheticSpec() = runTest {
        val device = object : AbstractDevice("plain".asName(), DeviceRuntime(freshContext())) {
            override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
                OperationOutcome.Ok(Meta(7))

            override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
                OperationOutcome.OkUnit

            override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
                OperationOutcome.Ok(null)
        }
        var observerCalled = false
        val builder = PipelineBuilder().apply {
            observeRead { _, _, _ -> observerCalled = true }
        }
        val wrapped = wrapWithPipeline(device, builder, "plain", device.context, autoInstallDefaults = false)

        // No declared spec for "x" -> fail fast. The runtime does not fabricate
        // synthetic specs for arbitrary Meta calls.
        assertFailsWith<OperationFaultException> {
            wrapped.readProperty("x".asName())
        }
        assertEquals(false, observerCalled, "no spec → no executor → no observer")
    }
}
