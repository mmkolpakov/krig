package space.kscience.krig.ui.schema

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.flatMapSuspend
import space.kscience.krig.api.result.map
import space.kscience.krig.core.contracts.Device

public suspend fun Device.readDeviceFormState(
    schema: DeviceFormSchema,
    properties: Set<Name> = emptySet(),
): DeviceFormStateSnapshot = DeviceFormStateSnapshot(
    values = (schema.properties + schema.discoveredProperties)
        .filter { it.readable }
        .filter { property -> properties.isEmpty() || property.name in properties }
        .associate { property ->
            property.name to readObservedOutcome(property.name).map { observed -> observed.toDeviceFormObservedMeta() }
        },
)

public suspend fun Device.readDeviceFormPatch(
    schema: DeviceFormSchema,
    properties: Set<Name> = emptySet(),
): DeviceFormStatePatch = DeviceFormStatePatch(updates = readDeviceFormState(schema, properties).values)

public suspend fun Device.executeDeviceFormCommand(
    schema: DeviceFormSchema,
    envelope: DeviceFormCommandEnvelope,
): DeviceFormCommandResult {
    val command = schema.commands.firstOrNull { it.id == envelope.commandId }
    val outcome = if (command == null) {
        deviceFormCommandValidationFailure("Unknown form command '${envelope.commandId}'.")
    } else {
        executeKnownDeviceFormCommand(command, envelope)
    }
    return DeviceFormCommandResult(
        commandId = envelope.commandId,
        correlationId = envelope.correlationId,
        outcome = outcome,
    )
}

private suspend fun Device.executeKnownDeviceFormCommand(
    command: DeviceFormCommand,
    envelope: DeviceFormCommandEnvelope,
): OperationOutcome<DeviceFormCommandOutput> = when (command.kind) {
    DeviceFormCommandKind.ReadProperty,
    DeviceFormCommandKind.OpenTaskState,
        -> readObservedOutcome(command.target.name).map { observed ->
        DeviceFormCommandOutput.Observed(observed.toDeviceFormObservedMeta())
    }

    DeviceFormCommandKind.WriteProperty -> validateDeviceFormCommandInput(command, envelope).flatMapSuspend { input ->
        writePropertyOutcome(command.target.name, input).map { DeviceFormCommandOutput.Completed }
    }

    DeviceFormCommandKind.ExecuteAction,
    DeviceFormCommandKind.CancelTask,
        -> validateOptionalDeviceFormCommandInput(command, envelope).flatMapSuspend { input ->
        executeOutcome(command.target.name, input).map { output -> DeviceFormCommandOutput.MetaValue(output) }
    }

    DeviceFormCommandKind.SubscribeProperty ->
        deviceFormCommandValidationFailure("SubscribeProperty commands must be handled by a form stream adapter.")
}

private fun validateDeviceFormCommandInput(
    command: DeviceFormCommand,
    envelope: DeviceFormCommandEnvelope,
): OperationOutcome<Meta> {
    val input = envelope.input
        ?: return deviceFormCommandValidationFailure("Command '${command.id}' requires an input payload.")
    return if (command.inputDescriptor.validate(input)) {
        OperationOutcome.Ok(input)
    } else {
        deviceFormCommandValidationFailure("Command '${command.id}' input does not satisfy its MetaDescriptor.")
    }
}

private fun validateOptionalDeviceFormCommandInput(
    command: DeviceFormCommand,
    envelope: DeviceFormCommandEnvelope,
): OperationOutcome<Meta?> {
    val input = envelope.input
    return if (input == null || command.inputDescriptor.validate(input)) {
        OperationOutcome.Ok(input)
    } else {
        deviceFormCommandValidationFailure("Command '${command.id}' input does not satisfy its MetaDescriptor.")
    }
}

private fun <T> deviceFormCommandValidationFailure(message: String): OperationOutcome<T> =
    OperationOutcome.Fail(ValidationFault(details = faultDetails(message)))
