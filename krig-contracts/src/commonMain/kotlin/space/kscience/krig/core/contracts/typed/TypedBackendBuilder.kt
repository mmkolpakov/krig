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
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
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
    private var batchReadBody: (suspend (Collection<PropertyDescriptor>) -> Map<Name, DeviceOutcome<Meta>>)? = null
    private var closeBody: (() -> Unit)? = null

    /** Registers a typed reader for [spec]. */
    public fun <T> reader(spec: DevicePropertyContract<T>, body: suspend () -> T) {
        readers[spec.name] = ReaderEntry(spec, GenericTypedReader(body))
    }

    /** Registers a typed writer for [spec]. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>, body: suspend (T) -> Unit) {
        writers[spec.name] = WriterEntry(spec, GenericTypedWriter(body))
    }

    /** Registers a typed sampler for [spec]. */
    public fun <T> sampler(spec: DevicePropertyContract<T>, body: () -> TypedSampler<T>) {
        samplers[spec.name] = SamplerEntry(spec, body())
    }

    /** Registers a typed action for [spec]. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>, body: suspend (I) -> O?) {
        actions[spec.name] = ActionEntry(spec, GenericTypedAction(body))
    }

    /**
     * Registers an optional protocol-neutral batch reader.
     *
     * The body receives SDK property descriptors only. Protocol-specific grouping, addresses,
     * and retries belong to the external backend implementation that owns this function.
     */
    public fun batchReader(body: suspend (Collection<PropertyDescriptor>) -> Map<Name, DeviceOutcome<Meta>>) {
        check(batchReadBody == null) { "batchReader was already declared on this builder" }
        batchReadBody = body
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
        batchReadBody = batchReadBody,
        closeBody = closeBody,
    )
}

/** Builds a [TypedDeviceBackend] that exposes native typed handles and a Meta control-plane adapter. */
public fun typedBackend(block: TypedBackendBuilder.() -> Unit): TypedDeviceBackend {
    val builder = TypedBackendBuilder()
    block(builder)
    return builder.build()
}

private data class ReaderEntry<T>(
    val spec: DevicePropertyContract<T>,
    val reader: TypedReader<T>,
)

private data class WriterEntry<T>(
    val spec: MutableDevicePropertyContract<T>,
    val writer: TypedWriter<T>,
)

private data class SamplerEntry<T>(
    val spec: DevicePropertyContract<T>,
    val sampler: TypedSampler<T>,
)

private data class ActionEntry<I, O>(
    val spec: DeviceActionContract<I, O>,
    val action: TypedAction<I, O>,
)

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltTypedBackend(
    private val readers: Map<Name, ReaderEntry<*>>,
    private val writers: Map<Name, WriterEntry<*>>,
    private val samplers: Map<Name, SamplerEntry<*>>,
    private val actions: Map<Name, ActionEntry<*, *>>,
    private val batchReadBody: (suspend (Collection<PropertyDescriptor>) -> Map<Name, DeviceOutcome<Meta>>)?,
    private val closeBody: (() -> Unit)?,
) : BatchTypedDeviceBackend {

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? {
        val entry = readers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.reader as TypedReader<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? {
        val entry = writers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.writer as TypedWriter<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? {
        val entry = samplers[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.sampler as TypedSampler<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? {
        val entry = actions[spec.name] ?: return null
        checkCompatible(entry.spec, spec)
        return entry.action as TypedAction<I, O>
    }

    override fun propertySpec(name: Name): DevicePropertyContract<*>? =
        writers[name]?.spec ?: readers[name]?.spec ?: samplers[name]?.spec

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? =
        actions[name]?.spec

    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> {
        val entry = readers[property.name]
            ?: return backendFault("UNKNOWN_PROPERTY", "Unknown property '${property.name}'")
        return runCatchingDevice {
            @Suppress("UNCHECKED_CAST")
            val typed = entry as ReaderEntry<Any?>
            typed.spec.converter.convert(typed.reader.read())
        }
    }

    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> {
        val entry = writers[property.name]
            ?: return backendFault("PROPERTY_NOT_WRITABLE", "Property '${property.name}' is not writable")
        @Suppress("UNCHECKED_CAST")
        val typed = entry as WriterEntry<Any?>
        return when (val decoded = decodeMeta(typed.spec.converter, value, "property", property.name)) {
            is DeviceOutcome.Fail -> decoded
            is DeviceOutcome.Ok -> runCatchingDevice { typed.writer.write(decoded.value) }
        }
    }

    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> {
        val entry = actions[action.name]
            ?: return backendFault("UNKNOWN_ACTION", "Unknown action '${action.name}'")
        @Suppress("UNCHECKED_CAST")
        val typed = entry as ActionEntry<Any?, Any?>
        return when (val decoded = decodeMeta(typed.spec.inputConverter, argument ?: Meta.EMPTY, "action", action.name)) {
            is DeviceOutcome.Fail -> decoded
            is DeviceOutcome.Ok -> runCatchingDevice {
                typed.action.execute(decoded.value)?.let(typed.spec.outputConverter::convert)
            }
        }
    }

    context(device: DeviceEnvironment)
    override suspend fun readBatch(properties: Collection<PropertyDescriptor>): Map<Name, DeviceOutcome<Meta>> {
        val custom = batchReadBody
        if (custom != null) return custom(properties)
        return buildMap(properties.size) {
            properties.forEach { property ->
                put(property.name, read(property))
            }
        }
    }

    override fun close() {
        closeBody?.invoke()
    }
}

private fun checkCompatible(registered: DevicePropertyContract<*>, requested: DevicePropertyContract<*>) {
    check(registered.descriptor == requested.descriptor && registered.converter === requested.converter) {
        "Property '${requested.name}' was requested with a different descriptor or converter instance."
    }
}

private fun checkCompatible(registered: DeviceActionContract<*, *>, requested: DeviceActionContract<*, *>) {
    check(
        registered.descriptor == requested.descriptor &&
                registered.inputConverter === requested.inputConverter &&
                registered.outputConverter === requested.outputConverter,
    ) {
        "Action '${requested.name}' was requested with a different descriptor or converter instance."
    }
}

private fun backendFault(code: String, message: String): DeviceOutcome.Fail =
    DeviceOutcome.Fail(GenericDeviceFault(code = code, message = message))

private fun <T> decodeMeta(converter: MetaConverter<T>, value: Meta, kind: String, name: Name): DeviceOutcome<T> {
    try {
        converter.readOrNull(value)?.let { return DeviceOutcome.Ok(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceFaultException) {
        return DeviceOutcome.Fail(e.fault)
    } catch (e: Exception) {
        return validationFault(kind, name, e.message ?: e.toString())
    }
    return validationFault(kind, name, "Payload does not match the registered converter.")
}

private fun validationFault(kind: String, name: Name, message: String): DeviceOutcome.Fail =
    DeviceOutcome.Fail(
        ValidationFault(
            details = Meta {
                "kind" put kind
                "name" put name.toString()
                "message" put message
            },
        ),
    )
