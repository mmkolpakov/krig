@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.PerformancePitfall::class,
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
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.meta.DevicePropertySpec
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val seq: AtomicInt = AtomicInt(0)
private fun freshContext(): Context = Context("legacy-meta-${seq.addAndFetch(1)}")

/**
 * Proves that `device.readProperty(Name): Meta` and `writeProperty(Name, Meta)` route
 * through the full typed pipeline (gates → executor → observers) when the delegate
 * exposes a registered [DevicePropertySpec] via [Device.propertySpec].
 */
class LegacyMetaPathRoutingTest {

    private class DoubleCellDevice : AbstractDevice("cell".asName(), DeviceRuntime(freshContext())) {
        private val cell = atomic(0.0)

        val valueSpec = object : space.kscience.krig.core.meta.MutableDevicePropertySpec<DoubleCellDevice, Double> {
            override val name: Name = "value".asName()
            override val descriptor = PropertyDescriptor(
                name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE,
            )
            override val converter: MetaConverter<Double> = MetaConverter.double
            override suspend fun read(device: DoubleCellDevice): Double = device.cell.value
            override suspend fun write(device: DoubleCellDevice, value: Double) {
                device.cell.value = value
            }
        }

        override fun propertySpec(propertyName: Name): DevicePropertySpec<*, *>? =
            if (propertyName == valueSpec.name) valueSpec else null

        override suspend fun readProperty(propertyName: Name): Meta {
            check(propertyName == valueSpec.name)
            return MetaConverter.double.convert(cell.value)
        }

        override suspend fun writeProperty(propertyName: Name, value: Meta) {
            check(propertyName == valueSpec.name)
            cell.value = value.double ?: error("expected Double")
        }

        override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
    }

    @Test
    fun metaReadFiresObserversWhenSpecIsKnown() = runTest {
        val device = DoubleCellDevice()
        val observed = mutableListOf<Pair<Name, DeviceFault?>>()
        val builder = TypedPipelineBuilder().apply {
            addReadObserver(ReadObserver { spec, _, fault -> observed += spec.name to fault })
        }
        val wrapped = wrapWithTypedPipeline(device, builder, "cell", autoInstallDefaults = false)

        wrapped.writeProperty(device.valueSpec.name, MetaConverter.double.convert(42.0))
        val result = wrapped.readProperty(device.valueSpec.name)

        assertEquals(42.0, result.double)
        assertTrue(observed.any { it.first == device.valueSpec.name && it.second == null },
            "observer must see Meta read routed through the typed executor")
    }

    @Test
    fun metaReadOnUnknownNameFailsWithoutSyntheticSpec() = runTest {
        val device = object : AbstractDevice("plain".asName(), DeviceRuntime(freshContext())) {
            override suspend fun readProperty(propertyName: Name): Meta = Meta(7)
            override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
            override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
        }
        var observerCalled = false
        val builder = TypedPipelineBuilder().apply {
            addReadObserver(ReadObserver { _, _, _ -> observerCalled = true })
        }
        val wrapped = wrapWithTypedPipeline(device, builder, "plain", autoInstallDefaults = false)

        // No declared spec for "x" -> fail fast. The runtime no longer fabricates
        // synthetic legacy specs for arbitrary Meta calls.
        assertFailsWith<DeviceFaultException> {
            wrapped.readProperty("x".asName())
        }
        assertEquals(false, observerCalled, "no spec → no executor → no observer")
    }
}
