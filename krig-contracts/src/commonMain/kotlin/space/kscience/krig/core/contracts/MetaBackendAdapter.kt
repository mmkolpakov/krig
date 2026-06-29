package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.operationFault
import space.kscience.krig.api.faults.validationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.contracts.typed.TypedAction
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
) : TypedDeviceBackend {

    context(env: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
        val spec = propertySpecs[property.name]
            ?: return operationFault(OperationFaultTypes.UnknownProperty, "Unknown property '${property.name}'")
        spec.validateDescriptor(property)?.let { return it }
        return readSpecAsMeta(spec)
    }

    context(env: DeviceEnvironment)
    override suspend fun readObserved(property: PropertyDescriptor): OperationOutcome<ObservedValue<Meta?>> {
        val spec = propertySpecs[property.name]
            ?: return operationFault(OperationFaultTypes.UnknownProperty, "Unknown property '${property.name}'")
        spec.validateDescriptor(property)?.let { return it }
        return readObservedSpecAsMeta(env, spec)
    }

    context(env: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
        val spec = propertySpecs[property.name] as? MutableDevicePropertyContract<*>
            ?: return validationFault("Property '${property.name}' is not writable")
        spec.validateDescriptor(property)?.let { return it }
        return writeSpecFromMeta(spec, value)
    }

    context(env: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> {
        val spec = actionSpecs[action.name]
            ?: return operationFault(OperationFaultTypes.UnknownAction, "Unknown action '${action.name}'")
        spec.validateDescriptor(action)?.let { return it }
        return executeSpecFromMeta(spec, argument)
    }

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? =
        typed.reader(spec)

    override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? =
        typed.observedReader(spec)

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? =
        typed.writer(spec)

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        typed.sampler(spec)

    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? =
        typed.action(spec)

    override fun propertySpec(name: Name): DevicePropertyContract<*>? = propertySpecs[name]

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? = actionSpecs[name]

    override fun propertySpecs(): Map<Name, DevicePropertyContract<*>> = propertySpecs

    override fun actionSpecs(): Map<Name, DeviceActionContract<*, *>> = actionSpecs

    override fun close() {
        onClose()
    }

    private suspend fun <T> readSpecAsMeta(spec: DevicePropertyContract<T>): OperationOutcome<Meta> {
        val reader = typed.reader(spec)
            ?: return operationFault(OperationFaultTypes.UnsupportedValue, "No typed reader for '${spec.name}'")
        return runCatchingOperation { spec.converter.convert(reader.read()) }
    }

    private suspend fun <T> readObservedSpecAsMeta(
        env: DeviceEnvironment,
        spec: DevicePropertyContract<T>,
    ): OperationOutcome<ObservedValue<Meta?>> {
        val observedReader = typed.observedReader(spec)
        if (observedReader != null) {
            return runCatchingOperation {
                observedReader.readObserved().map { value -> value?.let(spec.converter::convert) }
            }
        }
        return when (val meta = readSpecAsMeta(spec)) {
            is OperationOutcome.Ok -> OperationOutcome.Ok(ObservedValue(meta.value, env.clock.now(), DataQuality.GOOD))
            is OperationOutcome.Fail -> meta
        }
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

    private fun DevicePropertyContract<*>.validateDescriptor(
        requested: PropertyDescriptor,
    ): OperationOutcome.Fail? {
        val expected = descriptor
        return when {
            requested.name != expected.name -> validationFault(
                "Property descriptor name '${requested.name}' does not match contract '${expected.name}'",
                property = requested.name,
            )

            requested.kind != expected.kind -> validationFault(
                "Property '${requested.name}' kind mismatch: expected ${expected.kind}, got ${requested.kind}",
                property = requested.name,
            )

            requested.valueTypeId != expected.valueTypeId -> validationFault(
                "Property '${requested.name}' type mismatch: expected ${expected.valueTypeId}, got ${requested.valueTypeId}",
                property = requested.name,
            )

            else -> null
        }
    }

    private fun DeviceActionContract<*, *>.validateDescriptor(
        requested: ActionDescriptor,
    ): OperationOutcome.Fail? =
        if (requested.name == descriptor.name) {
            null
        } else {
            validationFault("Action descriptor name '${requested.name}' does not match contract '${descriptor.name}'")
        }
}

/**
 * Builds a [TypedDeviceBackend] from a Meta-free [typed] backend plus its contract registry. The returned
 * backend preserves native typed handles and derives its `Meta` plane via converters (see
 * [MetaBackendAdapter]). When [typed] already exposes its own registry (e.g. a `TypedDeviceBackend`),
 * pass [propertySpecs]/[actionSpecs] from it.
 */
@OptIn(UnstableKrigForSubclassing::class)
public fun metaBackendOf(
    typed: TypedBackend,
    propertySpecs: Map<Name, DevicePropertyContract<*>>,
    actionSpecs: Map<Name, DeviceActionContract<*, *>> = emptyMap(),
    onClose: () -> Unit = {},
): TypedDeviceBackend = MetaBackendAdapter(typed, propertySpecs, actionSpecs, onClose)
