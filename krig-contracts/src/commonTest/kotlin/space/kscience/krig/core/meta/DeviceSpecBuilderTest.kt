@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceSpecBuilderTest {

    private object TestSpec : DeviceSpecBuilder<Device>() {
        val temperature by doubleProperty { 25.0 }
        val setpoint by mutableDoubleProperty(
            read = { 20.0 },
            write = { _ -> },
        )
        val raw by metaProperty { Meta.EMPTY }
        val reset by action(MetaConverter.meta, MetaConverter.meta) { it }
    }

    @Test
    fun propertySpecsRegistered() {
        assertEquals(3, TestSpec.propertySpecs.size)
        assertEquals("temperature", TestSpec.temperature.name.toString())
        assertEquals("setpoint", TestSpec.setpoint.name.toString())
        assertEquals("raw", TestSpec.raw.name.toString())
    }

    @Test
    fun actionSpecsRegistered() {
        assertEquals(1, TestSpec.actionSpecs.size)
        assertEquals("reset", TestSpec.reset.name.toString())
    }

    @Test
    fun propertyKindDefaults() {
        val tempSpec = TestSpec.temperature
        assertEquals(PropertyKind.PHYSICAL, tempSpec.descriptor.kind)
    }

    @Test
    fun standardPropertyTypeIdsAreStable() {
        val tempSpec = TestSpec.temperature
        val rawSpec = TestSpec.raw

        assertEquals(TypeIds.DOUBLE, tempSpec.descriptor.valueTypeId)
        assertEquals(TypeIds.META, rawSpec.descriptor.valueTypeId)
    }

    @Test
    fun customPropertyRequiresExplicitTypeId() {
        val custom = object : DeviceSpecBuilder<Device>() {
            val status by property(MetaConverter.string, "domain.Status") { "ok" }
        }

        assertEquals("domain.Status", custom.status.descriptor.valueTypeId)
    }

    @Test
    fun duplicatePropertySpecsAreRejected() {
        val builder = object : DeviceSpecBuilder<Device>() {}
        @Suppress("UNUSED_VARIABLE")
        val first = builder.registerPropertySpec(propertySpec("duplicate".asName()))

        val failure = assertFailsWith<IllegalStateException> {
            builder.registerPropertySpec(propertySpec("duplicate".asName()))
        }

        assertTrue("Duplicate property spec 'duplicate'" in failure.message.orEmpty())
    }

    @Test
    fun duplicateActionSpecsAreRejected() {
        val builder = object : DeviceSpecBuilder<Device>() {}
        @Suppress("UNUSED_VARIABLE")
        val first = builder.registerActionSpec(actionSpec("duplicate".asName()))

        val failure = assertFailsWith<IllegalStateException> {
            builder.registerActionSpec(actionSpec("duplicate".asName()))
        }

        assertTrue("Duplicate action spec 'duplicate'" in failure.message.orEmpty())
    }

    private fun propertySpec(name: Name): DevicePropertySpec<Device, Double> =
        object : DevicePropertySpec<Device, Double> {
            override val name: Name = name
            override val descriptor: PropertyDescriptor =
                PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
            override val converter: MetaConverter<Double> = MetaConverter.double
            override suspend fun read(device: Device): Double = 0.0
        }

    private fun actionSpec(name: Name): DeviceActionSpec<Device, Meta, Meta> =
        object : DeviceActionSpec<Device, Meta, Meta> {
            override val name: Name = name
            override val descriptor: ActionDescriptor = ActionDescriptor(name = name)
            override val inputConverter: MetaConverter<Meta> = MetaConverter.meta
            override val outputConverter: MetaConverter<Meta> = MetaConverter.meta
            override suspend fun execute(device: Device, input: Meta) = input
        }
}
