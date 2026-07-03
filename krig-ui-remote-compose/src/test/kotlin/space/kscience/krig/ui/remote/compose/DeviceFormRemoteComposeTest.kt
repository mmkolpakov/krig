package space.kscience.krig.ui.remote.compose

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.ui.schema.toDeviceFormSchema

class DeviceFormRemoteComposeTest {
    @Test
    fun rendersNonEmptyRemoteComposeDocument() {
        val schema = testManifest().toDeviceFormSchema()
        val document = schema.toRemoteComposeDocument()

        assertTrue(document.byteSize > 0)
        assertEquals(schema.schemaHash, document.schemaHash)
        assertEquals("KRig device form", document.contentDescription)
        assertEquals(listOf("rpm", "temperature", "reset"), document.trace.map { it.label })
    }

    @Test
    fun documentBytesAreDefensiveCopies() {
        val document = testManifest().toDeviceFormSchema().toRemoteComposeDocument()
        val firstCopy = document.toByteArray()
        val original = firstCopy[0]

        firstCopy[0] = (original + 1).toByte()

        assertNotEquals(original, firstCopy[0])
        assertEquals(original, document.toByteArray()[0])
    }

    @Test
    fun rendererOptionsLimitTraceNodes() {
        val schema = testManifest().toDeviceFormSchema()
        val document = schema.toRemoteComposeDocument(DeviceFormRemoteComposeOptions(maxNodes = 1))

        assertContentEquals(listOf("rpm"), document.trace.map { it.label })
    }
}

private fun testManifest() = manifestOf(
    id = "demo.remote.form".parseAsName(),
    properties = listOf(
        PropertyDescriptor(
            name = "rpm".asName(),
            kind = PropertyKind.MEASURED,
            valueTypeId = TypeIds.DOUBLE,
        ),
        PropertyDescriptor(
            name = "temperature".asName(),
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.DOUBLE,
        ),
    ).associateBy { it.name },
    actions = listOf(ActionDescriptor(name = "reset".asName())).associateBy { it.name },
    version = "1.0.0",
    deviceContractFqName = "demo.RemoteForm",
)
