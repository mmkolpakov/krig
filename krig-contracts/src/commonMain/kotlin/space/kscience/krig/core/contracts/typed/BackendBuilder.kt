package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.CancellationException
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.BackendCore
import space.kscience.krig.core.contracts.BackendHandlers
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceBackendDsl
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.toBinaryReaders
import space.kscience.krig.core.contracts.toMetaActions
import space.kscience.krig.core.contracts.toMetaReaders
import space.kscience.krig.core.contracts.toMetaWriters
import space.kscience.krig.core.contracts.toObservedReaders
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Backend builder for driver code. User code declares readers, writers,
 * samplers and actions against pure device contracts; unchecked casts stay
 * inside this SDK boundary.
 */
@DeviceBackendDsl
public class BackendBuilder internal constructor() {
    private val readers: MutableMap<Name, ReaderEntry<*>> = mutableMapOf()
    private val observedReaders: MutableMap<Name, ObservedReaderEntry<*>> = mutableMapOf()
    private val binaryReaders: MutableMap<Name, BinaryReaderEntry> = mutableMapOf()
    private val writers: MutableMap<Name, WriterEntry<*>> = mutableMapOf()
    private val samplers: MutableMap<Name, SamplerEntry<*>> = mutableMapOf()
    private val actions: MutableMap<Name, ActionEntry<*, *>> = mutableMapOf()
    private var batchMetaReadBody: BatchMetaReadBody? = null
    private var batchObservedReadBody: BatchObservedReadBody? = null
    private var batchBinaryReadBody: BatchBinaryReadBody? = null
    private var batchWriteBody: BatchWriteBody? = null
    private var closeBody: (() -> Unit)? = null

    /** Registers a typed reader for [spec]. */
    public fun <T> reader(spec: DevicePropertyContract<T>, body: suspend () -> T) {
        checkReaderSlot(spec.name)
        readers[spec.name] = ReaderEntry(spec, TypedReader(body))
    }

    /** Registers a typed reader that can provide sample quality and timestamp. */
    public fun <T> observedReader(spec: DevicePropertyContract<T>, body: suspend () -> ObservedValue<T>) {
        checkReaderSlot(spec.name)
        observedReaders[spec.name] = ObservedReaderEntry(spec, body)
    }

    /**
     * Registers a binary reader for [spec]. Use this for payloads that should not be
     * forced through a Meta tree on the hot path.
     */
    public fun binaryReader(spec: DevicePropertyContract<*>, body: suspend () -> Binary) {
        checkReaderSlot(spec.name)
        binaryReaders[spec.name] = BinaryReaderEntry(spec, body)
    }

    /** Registers a byte-array binary reader for [spec]. */
    public fun bytesReader(spec: DevicePropertyContract<*>, body: suspend () -> ByteArray) {
        binaryReader(spec) { body().asBinary() }
    }

    /** Registers a typed writer for [spec]. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>, body: suspend (T) -> Unit) {
        writers[spec.name] = WriterEntry(spec, TypedWriter(body))
    }

    /** Registers a typed sampler for [spec]. */
    public fun <T> sampler(spec: DevicePropertyContract<T>, body: () -> TypedSampler<T>) {
        samplers[spec.name] = SamplerEntry(spec, body())
    }

    /** Registers a typed action for [spec]. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>, body: suspend (I) -> O?) {
        actions[spec.name] = ActionEntry(spec, TypedAction(body))
    }

    /**
     * Registers an optional protocol-neutral batch reader.
     *
     * The body receives SDK property descriptors only. Protocol-specific grouping, addresses,
     * and retries belong to the external backend implementation that owns this function.
     */
    public fun batchMetaReader(body: BatchMetaReadBody) {
        check(batchMetaReadBody == null) { "batchMetaReader was already declared on this builder" }
        batchMetaReadBody = body
    }

    /**
     * Registers a batch reader that preserves quality and source timestamps.
     * Prefer this for protocol batch reads that surface status per item.
     */
    public fun batchObservedReader(body: BatchObservedReadBody) {
        check(batchObservedReadBody == null) { "batchObservedReader was already declared on this builder" }
        batchObservedReadBody = body
    }

    /**
     * Registers a batch binary reader for payloads that should not cross Meta.
     */
    public fun batchBinaryReader(body: BatchBinaryReadBody) {
        check(batchBinaryReadBody == null) { "batchBinaryReader was already declared on this builder" }
        batchBinaryReadBody = body
    }

    /**
     * Registers a batch writer for one physical write transaction.
     * The body returns one outcome per descriptor name.
     * If the whole transaction fails before per-property status exists, return the same
     * failure for each requested descriptor.
     */
    public fun batchWriter(body: BatchWriteBody) {
        check(batchWriteBody == null) { "batchWriter was already declared on this builder" }
        batchWriteBody = body
    }

    /** Optional close hook for driver-owned resources. */
    public fun onClose(block: () -> Unit) {
        check(closeBody == null) { "onClose was already declared on this builder" }
        closeBody = block
    }

    internal fun build(): TypedDeviceBackend {
        val readerEntries = readers.toMap()
        val observedEntries = observedReaders.toMap()
        val binaryEntries = binaryReaders.toMap()
        val writerEntries = writers.toMap()
        val samplerEntries = samplers.toMap()
        val actionEntries = actions.toMap()
        val core = BackendCore(
            BackendHandlers(
                metaReaders = readerEntries.toMetaReaders(::readEntryAsMeta),
                observedReaders = observedEntries.toObservedReaders(::readObservedEntryAsMeta),
                binaryReaders = binaryEntries.toBinaryReaders { it.reader() },
                writers = writerEntries.toMetaWriters(::writeEntry),
                actions = actionEntries.toMetaActions { name, entry, argument -> executeEntry(entry, name, argument) },
                batchObserved = batchObservedReadBody,
                batchMeta = batchMetaReadBody,
                batchBinary = batchBinaryReadBody,
                batchWrite = batchWriteBody,
                onClose = closeBody,
            ),
        )
        return BuiltTypedBackend(readerEntries, observedEntries, binaryEntries, writerEntries, samplerEntries, actionEntries, core)
    }

    private fun checkReaderSlot(name: Name) {
        check(name !in readers && name !in observedReaders && name !in binaryReaders) {
            "Reader for property '$name' was already declared on this builder"
        }
    }
}

/** Builds a [TypedDeviceBackend] with contract handles and a Meta control-plane adapter. */
public fun backend(block: BackendBuilder.() -> Unit): TypedDeviceBackend {
    val builder = BackendBuilder()
    block(builder)
    return builder.build()
}

private data class ReaderEntry<T>(
    val spec: DevicePropertyContract<T>,
    val reader: TypedReader<T>,
)

public typealias BatchMetaReadBody =
        suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Meta>>

public typealias BatchObservedReadBody =
        suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>

public typealias BatchBinaryReadBody =
        suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>

public typealias BatchWriteBody =
        suspend DeviceEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>

private data class ObservedReaderEntry<T>(
    val spec: DevicePropertyContract<T>,
    val reader: suspend () -> ObservedValue<T>,
)

private data class BinaryReaderEntry(
    val spec: DevicePropertyContract<*>,
    val reader: suspend () -> Binary,
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

/**
 * Concrete [TypedDeviceBackend]: owns only the typed data-plane handles and spec introspection.
 * The Meta control plane is delegated to a [BackendCore] built from the same entries, so dispatch,
 * faults, and batch fallbacks live in one place.
 */
@OptIn(UnstableKrigForSubclassing::class)
private class BuiltTypedBackend(
    private val readers: Map<Name, ReaderEntry<*>>,
    private val observedReaders: Map<Name, ObservedReaderEntry<*>>,
    private val binaryReaders: Map<Name, BinaryReaderEntry>,
    private val writers: Map<Name, WriterEntry<*>>,
    private val samplers: Map<Name, SamplerEntry<*>>,
    private val actions: Map<Name, ActionEntry<*, *>>,
    core: BackendCore,
) : TypedDeviceBackend, DeviceBackend by core {

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? {
        val entry = readers[spec.name]
        if (entry != null) {
            checkCompatible(entry.spec, spec)
            return entry.reader as TypedReader<T>
        }

        val observedEntry = observedReaders[spec.name] ?: return null
        checkCompatible(observedEntry.spec, spec)
        return TypedReader { (observedEntry as ObservedReaderEntry<T>).reader().value }
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
        writers[name]?.spec ?: readers[name]?.spec ?: observedReaders[name]?.spec ?: binaryReaders[name]?.spec ?: samplers[name]?.spec

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? =
        actions[name]?.spec
}

private suspend fun readObservedEntryAsMeta(entry: ObservedReaderEntry<*>): ObservedValue<Meta?> {
    val typed = entry.asObservedAnyEntry()
    val observed = typed.reader()
    return ObservedValue(
        value = typed.spec.converter.convert(observed.value),
        time = observed.time,
        quality = observed.quality,
    )
}

private suspend fun readEntryAsMeta(entry: ReaderEntry<*>): Meta {
    val typed = entry.asAnyEntry()
    return typed.spec.converter.convert(typed.reader.read())
}

private suspend fun writeEntry(entry: WriterEntry<*>, value: Meta): OperationOutcome<Unit> {
    val typed = entry.asAnyEntry()
    return when (val decoded = decodeMeta(typed.spec.converter, value, "property", typed.spec.name)) {
        is OperationOutcome.Fail -> decoded
        is OperationOutcome.Ok -> runCatchingOperation { typed.writer.write(decoded.value) }
    }
}

private suspend fun executeEntry(entry: ActionEntry<*, *>, name: Name, argument: Meta?): OperationOutcome<Meta?> {
    val typed = entry.asAnyEntry()
    return when (val decoded = decodeMeta(typed.spec.inputConverter, argument ?: Meta.EMPTY, "action", name)) {
        is OperationOutcome.Fail -> decoded
        is OperationOutcome.Ok -> runCatchingOperation {
            typed.action.execute(decoded.value)?.let(typed.spec.outputConverter::convert)
        }
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

private fun <T> decodeMeta(converter: MetaConverter<T>, value: Meta, kind: String, name: Name): OperationOutcome<T> {
    try {
        converter.readOrNull(value)?.let { return OperationOutcome.Ok(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OperationFaultException) {
        return OperationOutcome.Fail(e.fault)
    } catch (e: Exception) {
        return validationFault(kind, name, e.message ?: e.toString())
    }
    return validationFault(kind, name, "Payload does not match the registered converter.")
}

private fun validationFault(kind: String, name: Name, message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            details = faultDetails(message = message, kind = kind, name = name),
        ),
    )

@Suppress("UNCHECKED_CAST")
private fun ReaderEntry<*>.asAnyEntry(): ReaderEntry<Any?> =
    this as ReaderEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun ObservedReaderEntry<*>.asObservedAnyEntry(): ObservedReaderEntry<Any?> =
    this as ObservedReaderEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun WriterEntry<*>.asAnyEntry(): WriterEntry<Any?> =
    this as WriterEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun ActionEntry<*, *>.asAnyEntry(): ActionEntry<Any?, Any?> =
    this as ActionEntry<Any?, Any?>
