package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Synthesizes the `Meta` control-plane [DeviceBackend] surface from a Meta-free [TypedBackend].
 *
 * The adapter remains typed-first: native handles and spec registries live on the unbound backend,
 * while [bind] creates the concrete `Meta` operation surface for one device runtime.
 */
@OptIn(InternalKrigApi::class, UnstableKrigForSubclassing::class)
public class MetaBackendAdapter(
    private val typed: TypedBackend,
    private val propertySpecs: Map<Name, DevicePropertyContract<*>>,
    private val actionSpecs: Map<Name, DeviceActionContract<*, *>> = emptyMap(),
    private val onClose: () -> Unit = {},
) : TypedDeviceBackend {

    override fun bind(environment: BackendEnvironment): BoundDeviceBackend =
        BoundMetaBackend(environment)

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

    private inner class BoundMetaBackend(
        override val environment: BackendEnvironment,
    ) : BoundDeviceBackend {
        override suspend fun read(property: PropertyDescriptor): Meta {
            val spec = propertySpecs[property.name]
                ?: unknownProperty(property.name)
            spec.validateDescriptor(property)
            return readSpecAsMeta(spec)
        }

        override suspend fun readObserved(property: PropertyDescriptor): ObservedValue<Meta?> {
            val spec = propertySpecs[property.name]
                ?: unknownProperty(property.name)
            spec.validateDescriptor(property)
            return readObservedSpecAsMeta(environment, spec)
        }

        override suspend fun write(property: PropertyDescriptor, value: Meta) {
            val spec = propertySpecs[property.name] as? MutableDevicePropertyContract<*>
                ?: validationFailure("Property '${property.name}' is not writable")
            spec.validateDescriptor(property)
            writeSpecFromMeta(spec, value)
        }

        override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta? {
            val spec = actionSpecs[action.name]
                ?: unknownAction(action.name)
            spec.validateDescriptor(action)
            return executeSpecFromMeta(spec, argument)
        }

        override fun close() {
            onClose()
        }
    }

    private suspend fun <T> readSpecAsMeta(spec: DevicePropertyContract<T>): Meta {
        val reader = typed.reader(spec)
            ?: unsupported("No typed reader for '${spec.name}'")
        return spec.converter.convert(reader.read())
    }

    private suspend fun <T> readObservedSpecAsMeta(
        env: BackendEnvironment,
        spec: DevicePropertyContract<T>,
    ): ObservedValue<Meta?> {
        val observedReader = typed.observedReader(spec)
        if (observedReader != null) {
            return observedReader.readObserved().map { value -> value?.let(spec.converter::convert) }
        }
        return ObservedValue(readSpecAsMeta(spec), env.clock.now(), DataQuality.GOOD)
    }

    private suspend fun <T> writeSpecFromMeta(spec: MutableDevicePropertyContract<T>, value: Meta) {
        val writer = typed.writer(spec)
            ?: validationFailure("No typed writer for '${spec.name}'")
        writer.write(decodeMetaOutcome(spec.converter, value, "property", spec.name).getOrThrow())
    }

    private suspend fun <I, O> executeSpecFromMeta(
        spec: DeviceActionContract<I, O>,
        argument: Meta?,
    ): Meta? {
        val handle = typed.action(spec)
            ?: unknownAction(spec.name)
        val input = decodeMetaOutcome(spec.inputConverter, argument ?: Meta.EMPTY, "action", spec.name).getOrThrow()
        return handle.execute(input)?.let(spec.outputConverter::convert)
    }

    private fun DevicePropertyContract<*>.validateDescriptor(requested: PropertyDescriptor) {
        val expected = descriptor
        when {
            requested.name != expected.name -> validationFailure(
                "Property descriptor name '${requested.name}' does not match contract '${expected.name}'",
                requested.name,
            )

            requested.kind != expected.kind -> validationFailure(
                "Property '${requested.name}' kind mismatch: expected ${expected.kind}, got ${requested.kind}",
                requested.name,
            )

            requested.valueTypeId != expected.valueTypeId -> validationFailure(
                "Property '${requested.name}' type mismatch: expected ${expected.valueTypeId}, got ${requested.valueTypeId}",
                requested.name,
            )
        }
    }

    private fun DeviceActionContract<*, *>.validateDescriptor(requested: ActionDescriptor) {
        if (requested.name != descriptor.name) {
            validationFailure("Action descriptor name '${requested.name}' does not match contract '${descriptor.name}'")
        }
    }
}

/**
 * Builds a [TypedDeviceBackend] from a Meta-free [typed] backend plus its contract registry.
 */
@OptIn(UnstableKrigForSubclassing::class)
public fun metaBackendOf(
    typed: TypedBackend,
    propertySpecs: Map<Name, DevicePropertyContract<*>>,
    actionSpecs: Map<Name, DeviceActionContract<*, *>> = emptyMap(),
    onClose: () -> Unit = {},
): TypedDeviceBackend = MetaBackendAdapter(typed, propertySpecs, actionSpecs, onClose)
