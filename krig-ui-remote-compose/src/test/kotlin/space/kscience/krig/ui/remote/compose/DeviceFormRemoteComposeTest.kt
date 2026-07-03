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
import space.kscience.krig.api.result.ok
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.ui.schema.DeviceFormCommandKind
import space.kscience.krig.ui.schema.DeviceFormCommandOutput
import space.kscience.krig.ui.schema.DeviceFormCommandResult
import space.kscience.krig.ui.schema.DeviceFormStatePatch
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
        assertEquals(schema.commands.size, document.actionBridge.bindings.size)
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

    @Test
    fun hostActionBridgeMapsActionsToCommandEnvelopes() {
        val schema = testManifest().toDeviceFormSchema()
        val bridge = schema.toRemoteComposeActionBridge(actionPrefix = "krig.test")
        val actionBinding = bridge.bindings.first { it.command.kind == DeviceFormCommandKind.ExecuteAction }

        assertEquals("krig.test:${schema.schemaHash}:${actionBinding.command.id}", actionBinding.hostActionName)

        val envelope = bridge.requireEnvelopeFor(actionBinding.hostActionName, correlationId = "c-1")

        assertEquals(actionBinding.command.id, envelope.commandId)
        assertEquals("c-1", envelope.correlationId)
    }

    @Test
    fun bridgeMapsCommandResultsAndStatePatchesBackToRendererFrames() {
        val schema = testManifest().toDeviceFormSchema()
        val bridge = schema.toRemoteComposeActionBridge()
        val actionBinding = bridge.bindings.first { it.command.kind == DeviceFormCommandKind.ExecuteAction }
        val result = DeviceFormCommandResult(
            commandId = actionBinding.command.id,
            correlationId = "c-2",
            outcome = ok(DeviceFormCommandOutput.Completed),
        )

        val resultFrame = bridge.frameFor(result)
        val patchFrame = bridge.frameFor(DeviceFormStatePatch())

        assertEquals(actionBinding.hostActionName, resultFrame.hostActionName)
        assertEquals(result, resultFrame.commandResult)
        assertEquals(schema.schemaHash, patchFrame.schemaHash)
        assertEquals(DeviceFormStatePatch(), patchFrame.statePatch)
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
