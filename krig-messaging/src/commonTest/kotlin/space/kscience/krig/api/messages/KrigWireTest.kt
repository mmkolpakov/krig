package space.kscience.krig.api.messages

import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KrigWireTest {
    @Test
    fun formatsUseDottedKrigNamespace() {
        val pattern = Regex("krig\\.[a-z0-9-]+(\\.[a-z0-9-]+)*")

        KrigWireFormats.all.forEach { format ->
            assertTrue(pattern.matches(format), "Unexpected KRig wire format: $format")
        }
    }

    @Test
    fun headersUseKrigPrefix() {
        KrigWireHeaders.all.forEach { header ->
            assertTrue(header.startsWith("krig."), "Unexpected KRig wire header: $header")
        }
    }

    @Test
    fun deviceTopicsAppendNameTokensWithoutStringConcatenation() {
        val deviceId = "edge.lineA.pump".parseAsName()

        assertEquals("krig.devices.edge.lineA.pump".parseAsName(), KrigWireTopics.device(deviceId))
        assertEquals("krig.devices.edge.lineA.pump.manifest".parseAsName(), KrigWireTopics.deviceManifest(deviceId))
        assertEquals("krig.devices.edge.lineA.pump.messages".parseAsName(), KrigWireTopics.deviceMessages(deviceId))
        assertEquals("krig.devices.edge.lineA.pump.telemetry".parseAsName(), KrigWireTopics.deviceTelemetry(deviceId))
    }
}
