package space.kscience.krig.api.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceMessageTypeTest {

    @Test
    fun allDeviceMessageTypeNamesAreUnique() {
        val values = listOf(
            DeviceMessageType.PropertyChanged,
            DeviceMessageType.DeviceError,
            DeviceMessageType.ActionFault,
            DeviceMessageType.DeviceAttached,
            DeviceMessageType.DeviceDetached,
            DeviceMessageType.PropertyReadRequest,
            DeviceMessageType.PropertyReadResponse,
            DeviceMessageType.PropertyWriteRequest,
            DeviceMessageType.PropertyWriteResponse,
            DeviceMessageType.PropertyFault,
            DeviceMessageType.ActionExecuteRequest,
            DeviceMessageType.ActionExecuteResponse,
            DeviceMessageType.DeviceOnline,
            DeviceMessageType.DeviceOffline,
        )

        assertEquals(values.size, values.toSet().size)
        assertEquals(values.toSet(), DeviceMessageType.all)
    }

    @Test
    fun allDeviceMessageTypeNamesUseDottedLowercaseWireFormat() {
        val pattern = Regex("[a-z]+(\\.[a-z0-9-]+)+")

        DeviceMessageType.all.forEach { type ->
            assertTrue(pattern.matches(type), "Unexpected DeviceMessage type name: $type")
        }
    }
}
