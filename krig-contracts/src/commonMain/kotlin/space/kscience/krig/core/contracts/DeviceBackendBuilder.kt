package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.typed.BatchBinaryReadBody
import space.kscience.krig.core.contracts.typed.BatchMetaReadBody
import space.kscience.krig.core.contracts.typed.BatchObservedReadBody
import space.kscience.krig.core.contracts.typed.BatchWriteBody
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * DSL marker for [deviceBackend] builder blocks. Prevents accidental capture of an outer DSL's
 * receiver inside a nested block such as [DeviceBackendBuilder.onStep].
 */
@DslMarker
public annotation class DeviceBackendDsl

/**
 * Mutable cell with cross-thread visibility for cell-DSL properties. Device operations run on
 * arbitrary dispatcher threads, so a plain captured `var` would carry no visibility guarantee.
 * Reference reads and writes are atomic; compound-update atomicity is intentionally not promised —
 * the cell contract is "last write wins".
 */
private class VolatileCell<T>(initial: T) {
    @Volatile
    var value: T = initial
}

/**
 * Type-safe handle to a cell-DSL property declared via [DeviceBackendBuilder.readable] /
 * [DeviceBackendBuilder.writable] / [DeviceBackendBuilder.computed]. The backend body reads and
 * writes the property through this handle without touching `Meta`; dispatch is wired by name.
 */
public class ConnectionProperty<T> internal constructor(
    public val name: Name,
    private val getter: () -> T,
    private val setter: ((T) -> Unit)?,
) {
    /**
     * Current value. The handle's [internal][ConnectionProperty] constructor means external code
     * cannot obtain it to mutate, so a public setter is safe; writing a read-only handle fails fast.
     */
    public var value: T
        get() = getter()
        set(newValue) {
            val currentSetter = setter ?: error("Property '$name' is read-only")
            currentSetter(newValue)
        }

    public val isWritable: Boolean get() = setter != null
}

/**
 * Single builder for a [DeviceBackend]. Two declaration styles share one dispatch/fault/codec core:
 *
 * - **contract-first** ([reader], [observedReader], [binaryReader], [writer], [sampler], [action]) —
 *   for drivers that own I/O against pure device contracts and want native typed handles plus spec
 *   introspection;
 * - **cell sugar** ([readable], [writable], [computed]) — backing cells for stateful simulation
 *   devices that advance on [onStep].
 *
 * Both lower to one [TypedDeviceBackend]; a backend without native handles simply falls back to the
 * `Meta` plane. Declaring [onStep] makes the result a [SteppedBackend] as well.
 */
@DeviceBackendDsl
public class DeviceBackendBuilder internal constructor() {

    private val readers: MutableMap<Name, ReaderEntry<*>> = mutableMapOf()
    private val observedReaders: MutableMap<Name, ObservedReaderEntry<*>> = mutableMapOf()
    private val binaryReaders: MutableMap<Name, BinaryReaderEntry> = mutableMapOf()
    private val writers: MutableMap<Name, WriterEntry<*>> = mutableMapOf()
    private val samplers: MutableMap<Name, SamplerEntry<*>> = mutableMapOf()
    private val actions: MutableMap<Name, ActionEntry<*, *>> = mutableMapOf()

    private val cellReaders: MutableMap<Name, Pair<suspend () -> Any?, MetaConverter<*>>> = mutableMapOf()
    private val cellWriters: MutableMap<Name, suspend (Meta) -> OperationOutcome<Unit>> = mutableMapOf()
    private val metaActions: MutableMap<Name, suspend (Meta?) -> OperationOutcome<Meta?>> = mutableMapOf()

    private var batchMetaReadBody: BatchMetaReadBody? = null
    private var batchObservedReadBody: BatchObservedReadBody? = null
    private var batchBinaryReadBody: BatchBinaryReadBody? = null
    private var batchWriteBody: BatchWriteBody? = null
    private var stepBlock: ((Duration) -> Unit)? = null
    private var closeBlock: (() -> Unit)? = null

    // --- contract-first declarations ---------------------------------------------------------

    /** Registers a typed reader for [spec]. The body owns I/O, caching, and faults; the spec stays pure. */
    public fun <T> reader(spec: DevicePropertyContract<T>, body: suspend () -> T) {
        reserveReaderSlot(spec.name)
        readers[spec.name] = ReaderEntry(spec, TypedReader(body))
    }

    /** Registers a typed reader that also reports sample quality and source timestamp. */
    public fun <T> observedReader(spec: DevicePropertyContract<T>, body: suspend () -> ObservedValue<T>) {
        reserveReaderSlot(spec.name)
        observedReaders[spec.name] = ObservedReaderEntry(spec, body)
    }

    /** Registers a binary reader for payloads that must not be forced through a `Meta` tree on the hot path. */
    public fun binaryReader(spec: DevicePropertyContract<*>, body: suspend () -> Binary) {
        reserveReaderSlot(spec.name)
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

    /** Registers a typed action for [spec]; converters stay on the pure action contract. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>, body: suspend (I) -> O?) {
        actions[spec.name] = ActionEntry(spec, TypedAction(body))
    }

    // --- cell sugar --------------------------------------------------------------------------

    /**
     * Declares a read-only property backed by an internal mutable cell. The [onStep] body writes
     * `property.value` to publish a new sample; external readers observe it on the next read.
     */
    @IgnorableReturnValue
    public fun <T : Any> readable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> =
        readableCell(name.asName(), initial, converter)

    /** Read-only cell keyed by a typed [DevicePropertyContract]; preserves hierarchical names. */
    @IgnorableReturnValue
    public fun <T : Any> readable(spec: DevicePropertyContract<T>, initial: T): ConnectionProperty<T> =
        readableCell(spec.name, initial, spec.converter)

    /** Declares a writable property: external `Meta` writes also reach the backing cell. */
    @IgnorableReturnValue
    public fun <T : Any> writable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> =
        writableCell(name.asName(), initial, converter)

    /** Writable cell keyed by a typed [DevicePropertyContract]; preserves hierarchical names. */
    @IgnorableReturnValue
    public fun <T : Any> writable(spec: DevicePropertyContract<T>, initial: T): ConnectionProperty<T> =
        writableCell(spec.name, initial, spec.converter)

    /**
     * Declares a *computed* property derived on every read by [compute]. Use it for values that
     * depend on several cells (e.g. a total) without caching a separate cell.
     */
    @IgnorableReturnValue
    public fun computed(name: String, compute: () -> Double): ConnectionProperty<Double> =
        computedCell(name.asName(), compute)

    /** Computed property keyed by a typed [DevicePropertyContract]; preserves hierarchical names. */
    @IgnorableReturnValue
    public fun computed(spec: DevicePropertyContract<Double>, compute: () -> Double): ConnectionProperty<Double> =
        computedCell(spec.name, compute)

    private fun <T : Any> readableCell(key: Name, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> {
        reserveReaderSlot(key)
        val cell = VolatileCell(initial)
        cellReaders[key] = Pair({ cell.value }, converter)
        return ConnectionProperty(key, { cell.value }) { cell.value = it }
    }

    @OptIn(InternalKrigApi::class)
    private fun <T : Any> writableCell(key: Name, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> {
        reserveReaderSlot(key)
        val cell = VolatileCell(initial)
        cellReaders[key] = Pair({ cell.value }, converter)
        cellWriters[key] = { meta ->
            when (val decoded = decodeMetaOutcome(converter, meta, "property", key)) {
                is OperationOutcome.Fail -> decoded
                is OperationOutcome.Ok -> {
                    cell.value = decoded.value
                    OperationOutcome.OkUnit
                }
            }
        }
        return ConnectionProperty(key, { cell.value }) { cell.value = it }
    }

    private fun computedCell(key: Name, compute: () -> Double): ConnectionProperty<Double> {
        reserveReaderSlot(key)
        cellReaders[key] = Pair({ compute() }, MetaConverter.double)
        return ConnectionProperty(key, compute, setter = null)
    }

    // --- meta-plane actions ------------------------------------------------------------------

    /** Declares a raw `Meta`→`Meta` action by name; the body is suspendable for I/O. */
    public fun action(name: String, body: suspend (argument: Meta?) -> Meta?) {
        metaActions[name.asName()] = { argument -> runCatchingOperation { body(argument) } }
    }

    /** Declares a raw `Meta`→`Meta` action keyed by a typed [DeviceActionContract]. */
    public fun actionMeta(spec: DeviceActionContract<*, *>, body: suspend (argument: Meta?) -> Meta?) {
        metaActions[spec.name] = { argument -> runCatchingOperation { body(argument) } }
    }

    // --- batch operations --------------------------------------------------------------------

    /**
     * Registers an optional protocol-neutral batch reader. The body receives SDK property
     * descriptors only; protocol grouping, addresses, and retries belong to the backend that owns it.
     */
    public fun batchMetaReader(body: BatchMetaReadBody) {
        check(batchMetaReadBody == null) { "batchMetaReader was already declared on this builder" }
        batchMetaReadBody = body
    }

    /** Registers a batch reader that preserves per-item quality and source timestamps. */
    public fun batchObservedReader(body: BatchObservedReadBody) {
        check(batchObservedReadBody == null) { "batchObservedReader was already declared on this builder" }
        batchObservedReadBody = body
    }

    /** Registers a batch binary reader for payloads that must not cross `Meta`. */
    public fun batchBinaryReader(body: BatchBinaryReadBody) {
        check(batchBinaryReadBody == null) { "batchBinaryReader was already declared on this builder" }
        batchBinaryReadBody = body
    }

    /**
     * Registers a batch writer for one physical write transaction; the body returns one outcome per
     * descriptor name. If the transaction fails before per-property status exists, return that same
     * failure for each requested descriptor.
     */
    public fun batchWriter(body: BatchWriteBody) {
        check(batchWriteBody == null) { "batchWriter was already declared on this builder" }
        batchWriteBody = body
    }

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * Declares the step body invoked by a simulation scheduler with the tick `dt`. It may read and
     * write declared cells through their handles. Omitting [onStep] keeps the backend stateless.
     */
    public fun onStep(block: (dt: Duration) -> Unit) {
        check(stepBlock == null) { "onStep was already declared on this builder" }
        stepBlock = block
    }

    /** Optional `close()` body for driver-owned resources; a no-op when omitted. */
    public fun onClose(block: () -> Unit) {
        check(closeBlock == null) { "onClose was already declared on this builder" }
        closeBlock = block
    }

    @OptIn(InternalKrigApi::class)
    internal fun build(): TypedDeviceBackend {
        val readerEntries = readers.toMap()
        val observedEntries = observedReaders.toMap()
        val binaryEntries = binaryReaders.toMap()
        val writerEntries = writers.toMap()
        val samplerEntries = samplers.toMap()
        val actionEntries = actions.toMap()
        val core = BackendCore(
            BackendHandlers(
                metaReaders = readerEntries.toMetaReaders(::readEntryAsMeta) +
                        cellReaders.toMetaReaders { (read, converter) -> converter.convertAny(read()) },
                observedReaders = observedEntries.toObservedReaders(::readObservedEntryAsMeta),
                binaryReaders = binaryEntries.toBinaryReaders { it.reader() },
                writers = writerEntries.toMetaWriters(::writeEntry) +
                        cellWriters.toMetaWriters { write, value -> write(value) },
                actions = actionEntries.toMetaActions { name, entry, argument -> executeEntry(entry, name, argument) } +
                        metaActions.toMetaActions { _, body, argument -> body(argument) },
                batchObserved = batchObservedReadBody,
                batchMeta = batchMetaReadBody,
                batchBinary = batchBinaryReadBody,
                batchWrite = batchWriteBody,
                onClose = closeBlock,
            ),
        )
        val typed: TypedDeviceBackend =
            BuiltTypedBackend(readerEntries, observedEntries, binaryEntries, writerEntries, samplerEntries, actionEntries, core)
        return stepBlock?.let { SteppingTypedBackend(typed, it) } ?: typed
    }

    private fun reserveReaderSlot(name: Name) {
        check(name !in readers && name !in observedReaders && name !in binaryReaders && name !in cellReaders) {
            "Reader for property '$name' was already declared on this builder"
        }
    }
}

/**
 * Builds a [DeviceBackend] from [block]. The result is always a [TypedDeviceBackend] (native handles
 * when declared, `Meta` fallback otherwise) and additionally a [TypedSteppedBackend] when [onStep] is set.
 */
public fun deviceBackend(block: DeviceBackendBuilder.() -> Unit): TypedDeviceBackend =
    DeviceBackendBuilder().apply(block).build()

/**
 * Same DSL as [deviceBackend] but the declared return type is [TypedSteppedBackend]. [block] **must**
 * declare [onStep][DeviceBackendBuilder.onStep]; otherwise this fails fast. Prefer it when the caller
 * needs the stepped type statically (e.g. to pass into a `SimulationSession`).
 */
public fun steppedBackend(block: DeviceBackendBuilder.() -> Unit): TypedSteppedBackend =
    deviceBackend(block) as? TypedSteppedBackend
        ?: error("steppedBackend { } requires an onStep { } block; use deviceBackend { } for state-less backends")

// --- backend entries and Meta adapters -------------------------------------------------------

private data class ReaderEntry<T>(val spec: DevicePropertyContract<T>, val reader: TypedReader<T>)

private data class ObservedReaderEntry<T>(val spec: DevicePropertyContract<T>, val reader: suspend () -> ObservedValue<T>)

private data class BinaryReaderEntry(val spec: DevicePropertyContract<*>, val reader: suspend () -> Binary)

private data class WriterEntry<T>(val spec: MutableDevicePropertyContract<T>, val writer: TypedWriter<T>)

private data class SamplerEntry<T>(val spec: DevicePropertyContract<T>, val sampler: TypedSampler<T>)

private data class ActionEntry<I, O>(val spec: DeviceActionContract<I, O>, val action: TypedAction<I, O>)

/**
 * Concrete [TypedDeviceBackend]: owns the typed data-plane handles and spec introspection. The `Meta`
 * control plane is delegated to a [BackendCore] built from the same entries, so dispatch, faults, and
 * batch fallbacks live in one place.
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
        readers[spec.name]?.let { entry ->
            checkCompatible(entry.spec, spec)
            return entry.reader as TypedReader<T>
        }
        val observedEntry = observedReaders[spec.name] ?: return null
        checkCompatible(observedEntry.spec, spec)
        return TypedReader { (observedEntry as ObservedReaderEntry<T>).reader().value }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? {
        val observedEntry = observedReaders[spec.name] ?: return null
        checkCompatible(observedEntry.spec, spec)
        return TypedObservedReader { (observedEntry as ObservedReaderEntry<T>).reader() }
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
        writers[name]?.spec ?: readers[name]?.spec ?: observedReaders[name]?.spec
            ?: binaryReaders[name]?.spec ?: samplers[name]?.spec

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? = actions[name]?.spec

    override fun propertySpecs(): Map<Name, DevicePropertyContract<*>> = buildMap {
        samplers.forEach { (name, entry) -> put(name, entry.spec) }
        binaryReaders.forEach { (name, entry) -> put(name, entry.spec) }
        observedReaders.forEach { (name, entry) -> put(name, entry.spec) }
        readers.forEach { (name, entry) -> put(name, entry.spec) }
        // Writers last: for a property that is both readable and writable the mutable spec wins,
        // matching the lookup order of [propertySpec].
        writers.forEach { (name, entry) -> put(name, entry.spec) }
    }

    override fun actionSpecs(): Map<Name, DeviceActionContract<*, *>> =
        actions.mapValues { (_, entry) -> entry.spec }
}

/** Adds a [SteppedBackend.step] body while keeping the full [TypedDeviceBackend] surface. */
@OptIn(UnstableKrigForSubclassing::class)
private class SteppingTypedBackend(
    backend: TypedDeviceBackend,
    private val stepBody: (Duration) -> Unit,
) : TypedDeviceBackend by backend, TypedSteppedBackend {
    override fun step(dt: Duration): Unit = stepBody(dt)
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

@OptIn(InternalKrigApi::class)
private suspend fun writeEntry(entry: WriterEntry<*>, value: Meta): OperationOutcome<Unit> {
    val typed = entry.asAnyEntry()
    return when (val decoded = decodeMetaOutcome(typed.spec.converter, value, "property", typed.spec.name)) {
        is OperationOutcome.Fail -> decoded
        is OperationOutcome.Ok -> runCatchingOperation { typed.writer.write(decoded.value) }
    }
}

@OptIn(InternalKrigApi::class)
private suspend fun executeEntry(entry: ActionEntry<*, *>, name: Name, argument: Meta?): OperationOutcome<Meta?> {
    val typed = entry.asAnyEntry()
    return when (val decoded = decodeMetaOutcome(typed.spec.inputConverter, argument ?: Meta.EMPTY, "action", name)) {
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

@Suppress("UNCHECKED_CAST")
private fun ReaderEntry<*>.asAnyEntry(): ReaderEntry<Any?> = this as ReaderEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun ObservedReaderEntry<*>.asObservedAnyEntry(): ObservedReaderEntry<Any?> = this as ObservedReaderEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun WriterEntry<*>.asAnyEntry(): WriterEntry<Any?> = this as WriterEntry<Any?>

@Suppress("UNCHECKED_CAST")
private fun ActionEntry<*, *>.asAnyEntry(): ActionEntry<Any?, Any?> = this as ActionEntry<Any?, Any?>
