package space.kscience.krig.demo

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.ui.schema.DeviceFormCommandEnvelope
import space.kscience.krig.ui.schema.DeviceFormCommandKind
import space.kscience.krig.ui.schema.DeviceFormCommandOutput
import space.kscience.krig.ui.schema.DeviceFormNodeId
import space.kscience.krig.ui.schema.executeDeviceFormCommand
import space.kscience.krig.ui.schema.readDeviceFormPatch
import space.kscience.krig.ui.schema.readDeviceFormState
import space.kscience.krig.ui.schema.toDeviceFormSchema

internal data class DeviceFormIntegrationSnapshot(
    val schemaHash: String,
    val properties: List<Name>,
    val commands: List<DeviceFormCommandKind>,
    val initialStateProperties: List<Name>,
    val actionCommandId: DeviceFormNodeId,
    val actionAck: String?,
    val writeCommandId: DeviceFormNodeId,
    val patchProperties: List<Name>,
)

internal suspend fun deviceFormIntegrationSnapshot(): DeviceFormIntegrationSnapshot {
    val context = demoContext("device-form-demo")
    val pump = device("formPump", pumpBackend(), context) { manifest(PumpManifest) }

    return try {
        pump.write(PumpSpec.rpm, 1_100.0)

        val schema = PumpManifest.toDeviceFormSchema()
        val initialState = pump.readDeviceFormState(schema)
        val actionCommand = schema.commands.first { it.kind == DeviceFormCommandKind.ExecuteAction }
        val actionResult = pump.executeDeviceFormCommand(
            schema = schema,
            envelope = DeviceFormCommandEnvelope(
                commandId = actionCommand.id,
                input = MetaConverter.string.convert("reset"),
                correlationId = "demo-form-action",
            ),
        )
        val writeCommand = schema.commands.first {
            it.kind == DeviceFormCommandKind.WriteProperty && it.target.name == PumpSpec.rpm.name
        }
        pump.executeDeviceFormCommand(
            schema = schema,
            envelope = DeviceFormCommandEnvelope(
                commandId = writeCommand.id,
                input = MetaConverter.double.convert(1_250.0),
                correlationId = "demo-form-write",
            ),
        )
        val patch = pump.readDeviceFormPatch(schema)

        DeviceFormIntegrationSnapshot(
            schemaHash = schema.schemaHash,
            properties = schema.properties.map { it.name },
            commands = schema.commands.map { it.kind }.distinct(),
            initialStateProperties = initialState.values.keys.sortedBy { it.toString() },
            actionCommandId = actionCommand.id,
            actionAck = actionResult.outcome.stringOutputOrNull(),
            writeCommandId = writeCommand.id,
            patchProperties = patch.updates.keys.sortedBy { it.toString() },
        )
    } finally {
        pump.close()
        context.close()
    }
}

suspend fun deviceFormIntegrationDemo() {
    val snapshot = deviceFormIntegrationSnapshot()

    println("=== Device form integration ===")
    println("  schema: ${snapshot.schemaHash}")
    println("  properties: ${snapshot.properties.joinToString()}")
    println("  commands: ${snapshot.commands.joinToString()}")
    println("  action ${snapshot.actionCommandId} -> ${snapshot.actionAck}")
    println("  patch after ${snapshot.writeCommandId}: ${snapshot.patchProperties.joinToString()}")
    println("\nDone - device form integration demo complete.")
}

private fun OperationOutcome<DeviceFormCommandOutput>.stringOutputOrNull(): String? {
    val output = (this as? OperationOutcome.Ok)?.value as? DeviceFormCommandOutput.MetaValue
    return output?.value?.let(MetaConverter.string::readOrNull)
}

private fun MetaConverter<String>.readOrNull(meta: Meta): String? =
    runCatching { read(meta) }.getOrNull()
