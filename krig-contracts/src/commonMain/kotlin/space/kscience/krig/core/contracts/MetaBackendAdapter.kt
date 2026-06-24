package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.operationFault
import space.kscience.krig.api.faults.validationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Synthesizes the `Meta` control-plane [DeviceBackend] surface from a Meta-free [TypedBackend].
 *
 * A driver author implements only the typed/primitive plane ([TypedBackend.reader] / [writer] /
 * [action], plus the [propertySpecs]/[actionSpecs] registry) and wraps it with this adapter — the
 * `Meta` `read`/`write`/`execute` path is derived through each contract's `MetaConverter`. This is
 * the SDK "firewall": drivers stay free of `dataforge-meta`, while the control plane keeps its
 * schemaless [Meta] surface for scripts, dynamic access, and Workspace integration.
 *
 * Use [metaBackendOf] for the common case where specs and an [onClose] are supplied directly.
 */
@OptIn(UnstableKrigForSubclassing::class)
public class MetaBackendAdapter(
    private val typed: TypedBackend,
    private val propertySpecs: Map<Name, DevicePropertyContract<*>>,
    private val actionSpecs: Map<Name, DeviceActionContract<*, *>> = emptyMap(),
    private val onClose: () -> Unit = {},
) : DeviceBackend {

    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
        val spec = propertySpecs[property.name]
            ?: return operationFault(OperationFaultTypes.UnknownProperty, "Unknown property '${property.name}'")
        return readSpecAsMeta(spec)
    }

    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
        val spec = propertySpecs[property.name] as? MutableDevicePropertyContract<*>
            ?: return validationFault("Property '${property.name}' is not writable")
        return writeSpecFromMeta(spec, value)
    }

    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> {
        val spec = actionSpecs[action.name]
            ?: return operationFault(OperationFaultTypes.UnknownAction, "Unknown action '${action.name}'")
        return executeSpecFromMeta(spec, argument)
    }

    override fun close() {
        onClose()
    }

    private suspend fun <T> readSpecAsMeta(spec: DevicePropertyContract<T>): OperationOutcome<Meta> {
        val reader = typed.reader(spec)
            ?: return operationFault(OperationFaultTypes.UnsupportedValue, "No typed reader for '${spec.name}'")
        return runCatchingOperation { spec.converter.convert(reader.read()) }
    }

    private suspend fun <T> writeSpecFromMeta(spec: MutableDevicePropertyContract<T>, value: Meta): OperationOutcome<Unit> {
        val writer = typed.writer(spec)
            ?: return validationFault("No typed writer for '${spec.name}'")
        return runCatchingOperation { writer.write(spec.converter.read(value)) }
    }

    private suspend fun <I, O> executeSpecFromMeta(
        spec: DeviceActionContract<I, O>,
        argument: Meta?,
    ): OperationOutcome<Meta?> {
        val handle = typed.action(spec)
            ?: return operationFault(OperationFaultTypes.UnknownAction, "No typed action for '${spec.name}'")
        return runCatchingOperation {
            handle.execute(spec.inputConverter.read(argument ?: Meta.EMPTY))?.let(spec.outputConverter::convert)
        }
    }
}

/**
 * Builds a [DeviceBackend] from a Meta-free [typed] backend plus its contract registry. The returned
 * backend derives its `Meta` plane via converters (see [MetaBackendAdapter]). When [typed] already
 * exposes its own registry (e.g. a `TypedDeviceBackend`), pass [propertySpecs]/[actionSpecs] from it.
 */
@OptIn(UnstableKrigForSubclassing::class)
public fun metaBackendOf(
    typed: TypedBackend,
    propertySpecs: Map<Name, DevicePropertyContract<*>>,
    actionSpecs: Map<Name, DeviceActionContract<*, *>> = emptyMap(),
    onClose: () -> Unit = {},
): DeviceBackend = MetaBackendAdapter(typed, propertySpecs, actionSpecs, onClose)
