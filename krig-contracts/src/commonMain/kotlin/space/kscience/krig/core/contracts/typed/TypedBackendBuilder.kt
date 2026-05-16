package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.CancellationException
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.runCatchingDevice
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.DeviceBackendDsl
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name

/**
 * Typed-first backend builder for driver code. User code declares readers, writers,
 * samplers and actions against [DeviceSpecBuilder][space.kscience.krig.core.meta.DeviceSpecBuilder]
 * specs; unchecked casts stay inside this SDK boundary.
 */
@DeviceBackendDsl
public class TypedBackendBuilder internal constructor() {
    private val readers: MutableMap<Name, ReaderEntry<*>> = mutableMapOf()
    private val writers: MutableMap<Name, WriterEntry<*>> = mutableMapOf()
    private val samplers: MutableMap<Name, SamplerEntry<*>> = mutableMapOf()
    private val actions: MutableMap<Name, ActionEntry<*, *>> = mutableMapOf()
    private var closeBody: (() -> Unit)? = null

    /** Registers a typed reader for [spec]. */
    public fun <T> reader(spec: DevicePropertySpec<*, T>, body: suspend () -> T) {
        readers[spec.name] = ReaderEntry(spec, GenericTypedReader(body))
    }

    /** Registers a typed writer for [spec]. */
    public fun <T> writer(spec: MutableDevicePropertySpec<*, T>, body: suspend (T) -> Unit) {
        writers[spec.name] = WriterEntry(spec, GenericTypedWriter(body))
    }

    /** Registers a typed sampler for [spec]. */
    public fun <T> sampler(spec: DevicePropertySpec<*, T>, body: () -> TypedSampler<T>) {
        samplers[spec.name] = SamplerEntry(spec, body())
    }

    /** Registers a typed action for [spec]. */
    public fun <I, O> action(spec: DeviceActionSpec<*, I, O>, body: suspend (I) -> O?) {
        actions[spec.name] = ActionEntry(spec, GenericTypedAction(body))
    }

    /** Optional close hook for driver-owned resources. */
    public fun onClose(block: () -> Unit) {
        check(closeBody == null) { "onClose was already declared on this builder" }
        closeBody = block
    }

    internal fun build(): TypedDeviceBackend = BuiltTypedBackend(
        readers = readers.toMap(),
        writers = writers.toMap(),
        samplers = samplers.toMap(),
        actions = actions.toMap(),
        closeBody = closeBody,
    )
}

/** Builds a [TypedDeviceBackend] that exposes native typed handles and a Meta control-plane adapter. */
public fun typedBackend(block: TypedBackendBuilder.() -> Unit): TypedDeviceBackend {
    val builder = TypedBackendBuilder()
    builder.block()
    return builder.build()
}

private data class ReaderEntry<T>(
    val spec: DevicePropertySpec<*, T>,
    val reader: TypedReader<T>,
)

private data class WriterEntry<T>(
    val spec: MutableDevicePropertySpec<*, T>,
    val writer: TypedWriter<T>,
)

private data class SamplerEntry<T>(
    val spec: DevicePropertySpec<*, T>,
    val sampler: TypedSampler<T>,
)

private data class ActionEntry<I, O>(
    val spec: DeviceActionSpec<*, I, O>,
    val action: TypedAction<I, O>,
)

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltTypedBackend(
    private val readers: Map<Name, ReaderEntry<*>>,
    private val writers: Map<Name, WriterEntry<*>>,
    private val samplers: Map<Name, SamplerEntry<*>>,
    private val actions: Map<Name, ActionEntry<*, *>>,
    private val closeBody: (() -> Unit)?,
) : TypedDeviceBackend {

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertySpec<*, T>): TypedReader<T>? {
        val entry = readers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.reader as TypedReader<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T>? {
        val entry = writers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.writer as TypedWriter<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> sampler(spec: DevicePropertySpec<*, T>): TypedSampler<T>? {
        val entry = samplers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.sampler as TypedSampler<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> action(spec: DeviceActionSpec<*, I, O>): TypedAction<I, O>? {
        val entry = actions[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.action as TypedAction<I, O>
    }

    override fun propertySpec(name: Name): DevicePropertySpec<*, *>? =
        writers[name]?.spec ?: readers[name]?.spec ?: samplers[name]?.spec

    override fun actionSpec(name: Name): DeviceActionSpec<*, *, *>? =
        actions[name]?.spec

    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = runCatchingDevice {
        val entry = readers[property.name]
            ?: backendFault("UNKNOWN_PROPERTY", "Unknown property '${property.name}'")
        @Suppress("UNCHECKED_CAST")
        val typed = entry as ReaderEntry<Any?>
        typed.spec.converter.convert(typed.reader.read())
    }

    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> =
        runCatchingDevice {
            val entry = writers[property.name]
                ?: backendFault("PROPERTY_NOT_WRITABLE", "Property '${property.name}' is not writable")
            @Suppress("UNCHECKED_CAST")
            val typed = entry as WriterEntry<Any?>
            typed.writer.write(decodeMeta(typed.spec.converter, value, "property", property.name))
        }

    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> =
        runCatchingDevice {
            val entry = actions[action.name]
                ?: backendFault("UNKNOWN_ACTION", "Unknown action '${action.name}'")
            @Suppress("UNCHECKED_CAST")
            val typed = entry as ActionEntry<Any?, Any?>
            val decoded = decodeMeta(typed.spec.inputConverter, argument ?: Meta.EMPTY, "action", action.name)
            typed.action.execute(decoded)?.let(typed.spec.outputConverter::convert)
        }

    override fun close() {
        closeBody?.invoke()
    }
}

private fun checkCompatible(registered: DevicePropertySpec<*, *>, requested: DevicePropertySpec<*, *>) {
    check(registered.descriptor == requested.descriptor && registered.converter === requested.converter) {
        "Property '${requested.name}' was requested with a different descriptor or converter instance."
    }
}

private fun checkCompatible(registered: DeviceActionSpec<*, *, *>, requested: DeviceActionSpec<*, *, *>) {
    check(
        registered.descriptor == requested.descriptor &&
                registered.inputConverter === requested.inputConverter &&
                registered.outputConverter === requested.outputConverter,
    ) {
        "Action '${requested.name}' was requested with a different descriptor or converter instance."
    }
}

private fun backendFault(code: String, message: String): Nothing =
    throw DeviceFaultException(GenericDeviceFault(code = code, message = message))

private fun <T> decodeMeta(converter: MetaConverter<T>, value: Meta, kind: String, name: Name): T {
    try {
        converter.readOrNull(value)?.let { return it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceFaultException) {
        throw e
    } catch (e: Exception) {
        validationFault(kind, name, e.message ?: e.toString(), e)
    }
    validationFault(kind, name, "Payload does not match the registered converter.", null)
}

private fun validationFault(kind: String, name: Name, message: String, cause: Throwable?): Nothing =
    throw DeviceFaultException(
        ValidationFault(
            details = Meta {
                "kind" put kind
                "name" put name.toString()
                "message" put message
            },
        ),
        cause,
    )
