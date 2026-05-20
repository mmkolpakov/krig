@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.PerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.hook

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.wrapWithPipeline
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals

private val contextSeq: AtomicInt = AtomicInt(0)
@Suppress("SameParameterValue")
private fun freshContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

@OptIn(InternalKrigApi::class)
class PropertyReadRequestedTest {

    private class StubDevice : AbstractDevice("stub".asName(), DeviceRuntime(freshContext("stub"))) {
        override suspend fun readProperty(propertyName: Name): Meta = Meta(1)
        override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
        override suspend fun execute(actionName: Name, argument: Meta?) = null
    }

    private fun specOf(name: Name): DevicePropertySpec<*, Int> =
        object : DevicePropertySpec<AbstractDevice, Int> {
            override val name: Name = name
            override val descriptor = PropertyDescriptor(
                name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.INT,
            )
            override val converter: MetaConverter<Int> = MetaConverter.int
            override suspend fun read(device: AbstractDevice): Int = 1
        }

    @Test
    fun propertyReadRequestedFiresOnEveryTypedRead() = runTest {
        val observed = mutableListOf<Name>()
        val builder = PipelineBuilder()
        builder.on(PropertyReadRequested) { name -> observed += name }

        val device = wrapWithPipeline(StubDevice(), builder, "stub", autoInstallDefaults = false)
        device.reader(specOf("a".asName())).read().let { }
        device.reader(specOf("b".asName())).read().let { }
        device.reader(specOf("c".asName())).read().let { }

        assertEquals(listOf("a", "b", "c").map { it.asName() }, observed)
    }

    @Test
    fun noHandlersMeansNoBehaviouralDifference() = runTest {
        val builder = PipelineBuilder()
        val device = wrapWithPipeline(StubDevice(), builder, "stub", autoInstallDefaults = false)
        val result = device.reader(specOf("x".asName())).read()
        assertEquals(1, result)
    }
}
