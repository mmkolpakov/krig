package space.kscience.krig.api.identifiers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Permission ids are part of the ACL/audit wire surface: field boundaries must stay unambiguous
 * even when device or property names contain dots (hierarchical names) or the separator itself.
 */
class PermissionIdTest {

    @Test
    fun dottedNamesDoNotCollideAcrossFieldBoundaries() {
        val left = ControlsPermission.DeviceRead(device = "a", property = "b.c")
        val right = ControlsPermission.DeviceRead(device = "a.b", property = "c")

        assertNotEquals(left.id, right.id, "field boundaries must survive dotted names")
    }

    @Test
    fun separatorInsideFieldIsEscaped() {
        val tricky = ControlsPermission.DeviceRead(device = "a:b", property = "c")
        val plain = ControlsPermission.DeviceRead(device = "a", property = "b:c")

        assertNotEquals(tricky.id, plain.id)
    }

    @Test
    fun deviceAndPropertySubscribeStayDistinct() {
        val device = ControlsPermission.DeviceSubscribe("d")
        val property = ControlsPermission.DevicePropertySubscribe("d", "rpm")

        assertNotEquals(device.id, property.id)
        assertEquals("device.subscribe:d", device.id)
        assertEquals("device.subscribe:d:rpm", property.id)
    }
}
