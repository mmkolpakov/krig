package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.TypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Introspection is a public SDK function: a DSL-built device must expose its declared
 * properties/actions through [space.kscience.krig.core.contracts.Device.propertyDescriptors],
 * and typed DSL declarations must carry their actual [TypeIds], not the erased META.
 */
class DeviceIntrospectionTest {

    @Test
    fun dslDeviceExposesDeclaredDescriptors() = runTest {
        val device = device("pump", Context("introspection-1")) {
            propertyDouble("rpm") { 60.0 }
            mutableProperty("setpoint", 10.0)
            propertyString("label") { "main" }
            action("reset") { null }
        }
        try {
            val properties = device.propertyDescriptors
            assertEquals(setOf("rpm".asName(), "setpoint".asName(), "label".asName()), properties.keys)
            assertEquals(TypeIds.DOUBLE, properties.getValue("rpm".asName()).valueTypeId)
            assertEquals(TypeIds.DOUBLE, properties.getValue("setpoint".asName()).valueTypeId)
            assertEquals(TypeIds.STRING, properties.getValue("label".asName()).valueTypeId)
            assertTrue("reset".asName() in device.actionDescriptors)
        } finally {
            device.shutdown()
        }
    }
}
