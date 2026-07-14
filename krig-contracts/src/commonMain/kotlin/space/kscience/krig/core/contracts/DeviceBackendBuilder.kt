package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.attributes.mutable
import space.kscience.krig.api.result.getOrThrow
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
 * DSL marker for typed backend builder receivers. In nested marked builders, only the closest
 * receiver remains available implicitly; an outer receiver requires an explicit label.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
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
 * - **contract-first** ([reader], [observedReader], [binaryReader], [writer], [bind], [sampler],
 *   [action]) — for drivers that own I/O against pure device contracts and want native typed
 *   handles plus spec introspection;
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

    private val cellReaders: MutableMap<Name, CellReaderEntry> = mutableMapOf()
    private val cellWriters: MutableMap<Name, suspend (Meta) -> Unit> = mutableMapOf()
    private val metaActions: MutableMap<Name, suspend (Meta?) -> Meta?> = mutableMapOf()

    private var batchMetaReadBody: BatchMetaReadBody? = null
    private var batchObservedReadBody: BatchObservedReadBody? = null
    private var batchBinaryReadBody: BatchBinaryReadBody? = null
    private var batchWriteBody: BatchWriteBody? = null
    private var stepBlock: ((Duration) -> Unit)? = null
    private var closeBlock: (() -> Unit)? = null

    // --- contract-first declarations ---------------------------------------------------------

    /** Registers a typed reader for [spec]. The body owns I/O, caching, and faults; the spec stays pure. */
    public fun <T> reader(spec: DevicePropertyContract<T>, body: suspend () -> T) {
        reserveReaderSlot(spec, "reader")
        readers[spec.name] = ReaderEntry(spec, TypedReader(body))
    }

    /** Registers a typed reader that also reports sample quality and source timestamp. */
    public fun <T> observedReader(spec: DevicePropertyContract<T>, body: suspend () -> ObservedValue<T>) {
        reserveReaderSlot(spec, "observed reader")
        observedReaders[spec.name] = ObservedReaderEntry(spec, body)
    }

    /** Registers a binary reader for payloads that must not be forced through a `Meta` tree on the hot path. */
    public fun binaryReader(spec: DevicePropertyContract<*>, body: suspend () -> Binary) {
        reserveReaderSlot(spec, "binary reader")
        binaryReaders[spec.name] = BinaryReaderEntry(spec, body)
    }

    /** Registers a byte-array binary reader for [spec]. */
    public fun bytesReader(spec: DevicePropertyContract<*>, body: suspend () -> ByteArray) {
        binaryReader(spec) { body().asBinary() }
    }

    /** Registers a typed writer for [spec]. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>, body: suspend (T) -> Unit) {
        reserveWriterSlot(spec)
        writers[spec.name] = WriterEntry(spec, TypedWriter(body))
    }

    /**
     * Registers a typed reader and writer for [spec] as one declaration. All slot and compatibility
     * checks complete before either handler is installed, so a rejected binding leaves this builder unchanged.
     *
     * @throws IllegalStateException if either handler conflicts with an existing property registration.
     */
    public fun <T> bind(
        spec: MutableDevicePropertyContract<T>,
        read: suspend () -> T,
        write: suspend (T) -> Unit,
    ) {
        reserveReaderSlot(spec, "reader")
        reserveWriterSlot(spec)

        val readerEntry = ReaderEntry(spec, TypedReader(read))
        val writerEntry = WriterEntry(spec, TypedWriter(write))
        readers[spec.name] = readerEntry
        writers[spec.name] = writerEntry
    }

    /** Registers a typed sampler for [spec]. */
    public fun <T> sampler(spec: DevicePropertyContract<T>, body: () -> TypedSampler<T>) {
        reserveSamplerSlot(spec)
        samplers[spec.name] = SamplerEntry(spec, body())
    }

    /** Registers a typed action for [spec]; converters stay on the pure action contract. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>, body: suspend (I) -> O?) {
        reserveActionSlot(spec.name, "typed action")
        actions[spec.name] = ActionEntry(spec, TypedAction(body))
    }

    // --- cell sugar --------------------------------------------------------------------------

    /**
     * Declares a read-only property backed by an internal mutable cell. The [onStep] body writes
     * `property.value` to publish a new sample; external readers observe it on the next read.
     */
    @IgnorableReturnValue
    public fun <T : Any> readable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> =
        readableCell(name.asName(), initial, converter, spec = null)

    /**
     * Read-only cell keyed by a read-only [DevicePropertyContract]; preserves hierarchical names.
     *
     * @throws IllegalArgumentException if the contract or its descriptor declares mutability.
     */
    @IgnorableReturnValue
    public fun <T : Any> readable(spec: DevicePropertyContract<T>, initial: T): ConnectionProperty<T> {
        requireReadOnlyCellContract(spec, "readable")
        return readableCell(spec.name, initial, spec.converter, spec)
    }

    /** Declares a writable property: external `Meta` writes also reach the backing cell. */
    @IgnorableReturnValue
    public fun <T : Any> writable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> =
        writableCell(name.asName(), initial, converter, spec = null)

    /**
     * Writable cell keyed by a [MutableDevicePropertyContract]; preserves hierarchical names.
     *
     * @throws IllegalArgumentException if the contract descriptor does not declare mutability.
     */
    @IgnorableReturnValue
    public fun <T : Any> writable(spec: MutableDevicePropertyContract<T>, initial: T): ConnectionProperty<T> {
        requireMutableCellContract(spec)
        return writableCell(spec.name, initial, spec.converter, spec)
    }

    /**
     * Declares a *computed* property derived on every read by [compute]. Use it for values that
     * depend on several cells (e.g. a total) without caching a separate cell.
     */
    @IgnorableReturnValue
    public fun computed(name: String, compute: () -> Double): ConnectionProperty<Double> =
        computedCell(name.asName(), compute, spec = null)

    /**
     * Computed property keyed by a read-only [DevicePropertyContract]; preserves hierarchical names.
     *
     * @throws IllegalArgumentException if the contract or its descriptor declares mutability.
     */
    @IgnorableReturnValue
    public fun computed(spec: DevicePropertyContract<Double>, compute: () -> Double): ConnectionProperty<Double> {
        requireReadOnlyCellContract(spec, "computed")
        return computedCell(spec.name, compute, spec)
    }

    private fun <T : Any> readableCell(
        key: Name,
        initial: T,
        converter: MetaConverter<T>,
        spec: DevicePropertyContract<T>?,
    ): ConnectionProperty<T> {
        reserveCellSlot(key, spec)
        val cell = VolatileCell(initial)
        cellReaders[key] = CellReaderEntry({ cell.value }, converter, spec)
        return ConnectionProperty(key, { cell.value }) { cell.value = it }
    }

    @OptIn(InternalKrigApi::class)
    private fun <T : Any> writableCell(
        key: Name,
        initial: T,
        converter: MetaConverter<T>,
        spec: MutableDevicePropertyContract<T>?,
    ): ConnectionProperty<T> {
        reserveCellSlot(key, spec)
        val cell = VolatileCell(initial)
        cellReaders[key] = CellReaderEntry({ cell.value }, converter, spec)
        cellWriters[key] = { meta ->
            cell.value = decodeMetaOutcome(converter, meta, "property", key).getOrThrow()
        }
        return ConnectionProperty(key, { cell.value }) { cell.value = it }
    }

    private fun computedCell(
        key: Name,
        compute: () -> Double,
        spec: DevicePropertyContract<Double>?,
    ): ConnectionProperty<Double> {
        reserveCellSlot(key, spec)
        cellReaders[key] = CellReaderEntry(compute, spec?.converter ?: MetaConverter.double, spec)
        return ConnectionProperty(key, compute, setter = null)
    }

    // --- meta-plane actions ------------------------------------------------------------------

    /** Declares a raw `Meta`→`Meta` action by name; the body is suspendable for I/O. */
    public fun action(name: String, body: suspend (argument: Meta?) -> Meta?) {
        val key = name.asName()
        reserveActionSlot(key, "Meta action")
        metaActions[key] = body
    }

    /** Declares a raw `Meta`→`Meta` action keyed by a typed [DeviceActionContract]. */
    public fun actionMeta(spec: DeviceActionContract<*, *>, body: suspend (argument: Meta?) -> Meta?) {
        reserveActionSlot(spec.name, "Meta action")
        metaActions[spec.name] = body
    }

    // --- batch operations --------------------------------------------------------------------

    /**
     * Registers an optional protocol-neutral batch reader. The body receives SDK property
     * descriptors only; protocol grouping, addresses, and retries belong to the backend that owns it.
     */
    public fun batchMetaReader(body: BatchMetaReadBody) {
        check(batchMetaReadBody == null && batchObservedReadBody == null) {
            "A batch value reader was already declared; cannot register batchMetaReader"
        }
        batchMetaReadBody = body
    }

    /** Registers a batch reader that preserves per-item quality and source timestamps. */
    public fun batchObservedReader(body: BatchObservedReadBody) {
        check(batchMetaReadBody == null && batchObservedReadBody == null) {
            "A batch value reader was already declared; cannot register batchObservedReader"
        }
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
        val cellEntries = cellReaders.toMap()
        val core = BackendCore(
            BackendHandlers(
                metaReaders = readerEntries.toMetaReaders(::readEntryAsMeta) +
                        cellEntries.toMetaReaders { entry -> entry.converter.convertAny(entry.read()) },
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
            BuiltTypedBackend(
                readerEntries,
                observedEntries,
                binaryEntries,
                writerEntries,
                samplerEntries,
                actionEntries,
                cellEntries,
                core,
            )
        return stepBlock?.let { SteppingTypedBackend(typed, it) } ?: typed
    }

    private fun reserveReaderSlot(spec: DevicePropertyContract<*>, requestedLane: String) {
        val name = spec.name
        val existingLane = existingReadLane(name) ?: if (name in cellReaders) "cell" else null
        check(existingLane == null) {
            "Property '$name' already has a $existingLane; cannot register a $requestedLane"
        }
        checkCompatiblePropertyRegistrations(spec)
    }

    private fun reserveWriterSlot(spec: MutableDevicePropertyContract<*>) {
        val name = spec.name
        val existingLane = when {
            name in writers -> "writer"
            name in cellReaders -> "cell"
            else -> null
        }
        check(existingLane == null) {
            "Property '$name' already has a $existingLane; cannot register a writer"
        }
        checkCompatiblePropertyRegistrations(spec)
    }

    private fun reserveSamplerSlot(spec: DevicePropertyContract<*>) {
        val name = spec.name
        val existingLane = if (name in samplers) "sampler" else null
        check(existingLane == null) {
            "Property '$name' already has a $existingLane; cannot register a sampler"
        }
        cellReaders[name]?.let { cell ->
            val cellSpec = cell.spec
            check(cellSpec != null) {
                "Property '$name' already has an untyped cell; cannot register a sampler"
            }
            checkCompatible(cellSpec, spec)
        }
        checkCompatiblePropertyRegistrations(spec)
    }

    private fun reserveCellSlot(name: Name, spec: DevicePropertyContract<*>?) {
        val existingLane = existingReadLane(name) ?: when {
            name in writers -> "writer"
            name in cellReaders -> "cell"
            else -> null
        }
        check(existingLane == null) {
            "Property '$name' already has a $existingLane; cannot register a cell"
        }
        samplers[name]?.let { sampler ->
            check(spec != null) {
                "Property '$name' already has a sampler contract; cannot register an untyped cell"
            }
            checkCompatible(sampler.spec, spec)
        }
    }

    private fun reserveActionSlot(name: Name, requestedLane: String) {
        val existingLane = when {
            name in actions -> "typed action"
            name in metaActions -> "Meta action"
            else -> null
        }
        check(existingLane == null) {
            "Action '$name' already has a $existingLane; cannot register a $requestedLane"
        }
    }

    private fun existingReadLane(name: Name): String? = when {
        name in readers -> "reader"
        name in observedReaders -> "observed reader"
        name in binaryReaders -> "binary reader"
        else -> null
    }

    private fun checkCompatiblePropertyRegistrations(spec: DevicePropertyContract<*>) {
        val name = spec.name
        readers[name]?.let { checkCompatible(it.spec, spec) }
        observedReaders[name]?.let { checkCompatible(it.spec, spec) }
        binaryReaders[name]?.let { checkCompatible(it.spec, spec) }
        writers[name]?.let { checkCompatible(it.spec, spec) }
        samplers[name]?.let { checkCompatible(it.spec, spec) }
    }

    private fun requireReadOnlyCellContract(spec: DevicePropertyContract<*>, declaration: String) {
        require(spec !is MutableDevicePropertyContract<*>) {
            "A $declaration cell for property '${spec.name}' requires a read-only contract; use writable for a mutable contract"
        }
        require(!spec.descriptor.mutable) {
            "A $declaration cell for property '${spec.name}' requires a descriptor with mutable=false"
        }
    }

    private fun requireMutableCellContract(spec: MutableDevicePropertyContract<*>) {
        require(spec.descriptor.mutable) {
            "A writable cell for property '${spec.name}' requires a descriptor with mutable=true"
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

private data class CellReaderEntry(
    val read: () -> Any?,
    val converter: MetaConverter<*>,
    val spec: DevicePropertyContract<*>?,
)

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
    private val cells: Map<Name, CellReaderEntry>,
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
            ?: binaryReaders[name]?.spec ?: cells[name]?.spec ?: samplers[name]?.spec

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? = actions[name]?.spec

    override fun propertySpecs(): Map<Name, DevicePropertyContract<*>> = buildMap {
        samplers.forEach { (name, entry) -> put(name, entry.spec) }
        binaryReaders.forEach { (name, entry) -> put(name, entry.spec) }
        observedReaders.forEach { (name, entry) -> put(name, entry.spec) }
        readers.forEach { (name, entry) -> put(name, entry.spec) }
        // A typed cell is the complete property declaration, so it is canonical when composed
        // with a compatible sampler regardless of registration order.
        cells.forEach { (name, entry) -> entry.spec?.let { put(name, it) } }
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
private suspend fun writeEntry(entry: WriterEntry<*>, value: Meta) {
    val typed = entry.asAnyEntry()
    val decoded = decodeMetaOutcome(typed.spec.converter, value, "property", typed.spec.name).getOrThrow()
    typed.writer.write(decoded)
}

@OptIn(InternalKrigApi::class)
private suspend fun executeEntry(entry: ActionEntry<*, *>, name: Name, argument: Meta?): Meta? {
    val typed = entry.asAnyEntry()
    val decoded = decodeMetaOutcome(typed.spec.inputConverter, argument ?: Meta.EMPTY, "action", name).getOrThrow()
    return typed.action.execute(decoded)?.let(typed.spec.outputConverter::convert)
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
