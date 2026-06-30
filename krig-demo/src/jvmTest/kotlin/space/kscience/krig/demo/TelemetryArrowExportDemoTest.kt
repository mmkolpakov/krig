package space.kscience.krig.demo

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertTrue

class TelemetryArrowExportDemoTest {

    @Test
    fun writesArrowIpcFileFromCommonEdgePayload() {
        val path = createTempFile(prefix = "krig-telemetry-", suffix = ".arrow")
        try {
            val bytes = writeTelemetryArrowExport(path, sampleCount = 8)

            assertTrue(bytes > 0L, "Arrow IPC export should create a non-empty file.")
        } finally {
            path.deleteIfExists()
        }
    }
}
