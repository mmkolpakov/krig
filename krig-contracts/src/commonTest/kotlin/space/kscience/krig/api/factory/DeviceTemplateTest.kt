@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
)

package space.kscience.krig.api.factory

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.required
import space.kscience.dataforge.meta.descriptors.value
import space.kscience.krig.core.contracts.SimulatedDoubleSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTemplateTest {

    private val descriptor = MetaDescriptor {
        value("setpoint", ValueType.NUMBER) { required() }
    }

    private val factory = DeviceFactory("thermo") { context ->
        SimulatedDoubleSource(context = context)
    }

    @Test
    fun templateValidatesConfigAgainstDescriptor() {
        val template = factory.asTemplate(descriptor)

        assertTrue(template.validate(Meta { "setpoint" put 20.0 }), "valid config must pass")
        assertFalse(template.validate(Meta { }), "config missing the required 'setpoint' must fail")
    }

    @Test
    fun templateBuildsDevice() {
        val template = factory.asTemplate(descriptor)
        val device = template.build(Context("thermo-test"), Meta { "setpoint" put 20.0 })
        assertEquals("simulated-double-source", device.name.toString())
        assertEquals(factory.id, template.id)
    }
}
