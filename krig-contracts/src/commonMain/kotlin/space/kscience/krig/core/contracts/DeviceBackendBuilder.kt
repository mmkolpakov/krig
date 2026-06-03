package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.validationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.time.Duration

/**
 * DSL marker for [deviceBackend] builder blocks. Prevents accidental nesting
 * of unrelated receivers (e.g. capturing an outer DSL's `this` inside an
 * [DeviceBackendBuilder.onStep] block).
 */
@DslMarker
public annotation class DeviceBackendDsl

/**
 * A type-safe handle to a property exposed by a [DeviceBackend] built via
 * [deviceBackend]. Lets the backend body read and write the property
 * without going through `Meta` boilerplate.
 *
 * The handle is created by [DeviceBackendBuilder.readable] /
 * [DeviceBackendBuilder.writable] / [DeviceBackendBuilder.computed]; it
 * is wired into the backend's read/write dispatch automatically by name.
 */
public class ConnectionProperty<T> internal constructor(
    public val name: Name,
    private val getter: () -> T,
    private val setter: ((T) -> Unit)?,
) {
    /**
     * Current value. The getter returns the cell value; the setter updates it.
     * The setter is public because the `ConnectionProperty` handle itself is only
     * constructible from within the `deviceBackend { }` DSL (internal constructor),
     * so external code cannot obtain a handle to mutate.
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
 * Builder DSL for declaring a [DeviceBackend] without subclassing.
 *
 * Declare properties, actions, and an optional [onStep] block; the builder
 * generates a complete backend with dispatch, close, and diagnostics.
 */
@DeviceBackendDsl
public class DeviceBackendBuilder internal constructor() {

    private val readers: MutableMap<Name, Pair<suspend () -> Any?, MetaConverter<*>>> = mutableMapOf()
    private val observedReaders: MutableMap<Name, Pair<suspend () -> ObservedValue<Any?>, MetaConverter<*>>> =
        mutableMapOf()
    private val binaryReaders: MutableMap<Name, suspend () -> Binary> = mutableMapOf()
    private val writers: MutableMap<Name, suspend (Meta) -> OperationOutcome<Unit>> = mutableMapOf()
    private val actions: MutableMap<Name, suspend (Meta?) -> Meta?> = mutableMapOf()
    private var batchObservedReadBody:
            (suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>)? = null
    private var batchBinaryReadBody:
            (suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>)? = null
    private var batchWriteBody:
            (suspend DeviceEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>)? = null
    private var stepBlock: ((Duration) -> Unit)? = null
    private var closeBlock: (() -> Unit)? = null

    /**
     * Declares a read-only property backed by an internal mutable cell.
     * The body of [onStep] writes to `property.value` to publish the new sample,
     * then external readers see it via `connection.read(descriptor)`.
     *
     * @param name Property name used for dispatch.
     * @param initial Initial cell value.
     * @param converter Handles conversion between `T` and `Meta`.
     */
    @IgnorableReturnValue
    public fun <T : Any> readable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> {
        val key = name.asName()
        checkReaderSlot(key)
        var cell = initial
        readers[key] = Pair({ cell }, converter)
        return ConnectionProperty(key, { cell }, { newValue -> cell = newValue })
    }

    /**
     * Declares a writable property -- same as [readable], but external
     * `connection.write(descriptor, meta)` calls also reach the cell.
     *
     * @param name Property name used for dispatch.
     * @param initial Initial cell value.
     * @param converter Handles conversion between `T` and `Meta`.
     */
    @IgnorableReturnValue
    public fun <T : Any> writable(name: String, initial: T, converter: MetaConverter<T>): ConnectionProperty<T> {
        val key = name.asName()
        checkReaderSlot(key)
        var cell = initial
        readers[key] = Pair({ cell }, converter)
        writers[key] = { meta ->
            val decoded = converter.readOrNull(meta)
            if (decoded == null) {
                validationFault("Property '$key': cannot decode Meta to ${converter::class.simpleName}")
            } else {
                cell = decoded
                OperationOutcome.OkUnit
            }
        }
        return ConnectionProperty(key, { cell }, { newValue -> cell = newValue })
    }

    /**
     * Declares a *computed* property -- its value is derived on every read by
     * invoking [compute]. Use this for properties that depend on multiple
     * underlying cells (e.g. total mass = mass1 + mass2 + water) without
     * needing a separate cached cell.
     */
    @IgnorableReturnValue
    public fun computed(name: String, compute: () -> Double): ConnectionProperty<Double> {
        val key = name.asName()
        checkReaderSlot(key)
        readers[key] = Pair({ compute() }, MetaConverter.double)
        return ConnectionProperty(key, compute, null)
    }

    /**
     * Declares an action that takes a [Meta] argument and returns a [Meta]
     * result. The body is suspendable so it can perform I/O against external
     * resources when needed.
     */
    public fun action(name: String, body: suspend (argument: Meta?) -> Meta?) {
        actions[name.asName()] = body
    }

    /**
     * Declares an explicit read operation for an externally managed property.
     *
     * Preferred replacement for executable property specs: the contract stays pure,
     * while the backend block owns I/O, caching, and faults.
     */
    public fun <T> read(spec: DevicePropertyContract<T>, body: suspend () -> T?) {
        checkReaderSlot(spec.name)
        readers[spec.name] = Pair(body, spec.converter)
    }

    /** Declares an observed read operation with explicit quality and sample time. */
    public fun <T> readObserved(spec: DevicePropertyContract<T>, body: suspend () -> ObservedValue<T>) {
        checkReaderSlot(spec.name)
        observedReaders[spec.name] = Pair(body, spec.converter)
    }

    /** Declares a binary read operation for payloads that should not cross Meta. */
    public fun readBinary(spec: DevicePropertyContract<*>, body: suspend () -> Binary) {
        checkReaderSlot(spec.name)
        binaryReaders[spec.name] = body
    }

    /** Declares a byte-array binary read operation. */
    public fun readBytes(spec: DevicePropertyContract<*>, body: suspend () -> ByteArray) {
        readBinary(spec) { body().asBinary() }
    }

    /** Declares one physical batch read that preserves per-property quality. */
    public fun batchObservedReader(
        body: suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>,
    ) {
        check(batchObservedReadBody == null) { "batchObservedReader was already declared on this builder" }
        batchObservedReadBody = body
    }

    /** Declares one physical batch binary read. */
    public fun batchBinaryReader(
        body: suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>,
    ) {
        check(batchBinaryReadBody == null) { "batchBinaryReader was already declared on this builder" }
        batchBinaryReadBody = body
    }

    /** Declares one physical batch write transaction. */
    public fun batchWriter(
        body: suspend DeviceEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>,
    ) {
        check(batchWriteBody == null) { "batchWriter was already declared on this builder" }
        batchWriteBody = body
    }

    /** Declares an explicit write operation for an externally managed mutable property. */
    public fun <T> write(spec: MutableDevicePropertyContract<T>, body: suspend (value: T) -> Unit) {
        writers[spec.name] = { meta ->
            val decoded = spec.converter.readOrNull(meta)
            if (decoded == null) {
                validationFault("Property '${spec.name}': cannot decode Meta to ${spec.converter::class.simpleName}")
            } else {
                runCatchingOperation { body(decoded) }
            }
        }
    }

    /** Declares a typed action operation; converters remain on the pure action contract. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>, body: suspend (argument: I) -> O?) {
        actions[spec.name] = { meta ->
            val input = spec.inputConverter.readOrNull(meta ?: Meta.EMPTY)
                ?: throw IllegalArgumentException(
                    "Action '${spec.name}': cannot decode Meta argument to ${spec.inputConverter::class.simpleName}",
                )
            body(input)?.let(spec.outputConverter::convert)
        }
    }

    /** Read-only property keyed by a typed [DevicePropertyContract]. */
    @IgnorableReturnValue
    public fun <T : Any> readable(spec: DevicePropertyContract<T>, initial: T): ConnectionProperty<T> =
        readable(spec.name.toString(), initial, spec.converter)

    /** Writable property keyed by a typed [DevicePropertyContract]. */
    @IgnorableReturnValue
    public fun <T : Any> writable(spec: DevicePropertyContract<T>, initial: T): ConnectionProperty<T> =
        writable(spec.name.toString(), initial, spec.converter)

    /** Computed property keyed by a typed [DevicePropertyContract]. */
    @IgnorableReturnValue
    public fun computed(
        spec: DevicePropertyContract<Double>,
        compute: () -> Double,
    ): ConnectionProperty<Double> =
        computed(spec.name.toString(), compute)

    /** Raw Meta action keyed by a typed [DeviceActionContract]. */
    public fun actionMeta(spec: DeviceActionContract<*, *>, body: suspend (argument: Meta?) -> Meta?) {
        actions[spec.name] = body
    }

    /**
     * Declares the step body, invoked by the orchestrator with the `dt`
     * for the current tick. May read and write declared properties via
     * their handles. Omitting [onStep] makes the resulting backend
     * stateless: its [SteppedBackend.step] becomes the default no-op.
     */
    public fun onStep(block: (dt: Duration) -> Unit) {
        check(stepBlock == null) { "onStep was already declared on this builder" }
        stepBlock = block
    }

    /**
     * Optional `close()` body -- invoked when the backend is shut down.
     * If omitted, `close()` is a no-op.
     */
    public fun onClose(block: () -> Unit) {
        check(closeBlock == null) { "onClose was already declared on this builder" }
        closeBlock = block
    }

    internal fun build(): DeviceBackend {
        val core = BackendCore(
            BackendHandlers(
                metaReaders = readers.toMetaReaders { (reader, converter) -> converter.convertAny(reader()) },
                observedReaders = observedReaders.toObservedReaders { (reader, converter) ->
                    val observed = reader()
                    ObservedValue(converter.convertAny(observed.value), observed.time, observed.quality)
                },
                binaryReaders = binaryReaders.toBinaryReaders { it() },
                writers = writers.toMetaWriters { writer, value -> writer(value) },
                actions = actions.toMetaActions { _, body, argument -> runCatchingOperation { body(argument) } },
                batchObserved = batchObservedReadBody,
                batchBinary = batchBinaryReadBody,
                batchWrite = batchWriteBody,
                onClose = closeBlock,
            ),
        )
        val step = stepBlock
        return if (step != null) SteppedBackend(core, step) else core
    }

    private fun checkReaderSlot(name: Name) {
        check(name !in readers && name !in observedReaders && name !in binaryReaders) {
            "Reader for property '$name' was already declared on this builder"
        }
    }
}

/**
 * Builds a [DeviceBackend] from the builder block. Returns a [SteppedBackend] if
 * [DeviceBackendBuilder.onStep] was declared; otherwise returns a plain [DeviceBackend].
 * Simulation schedulers pick up only the stepped cases.
 */
public fun deviceBackend(block: DeviceBackendBuilder.() -> Unit): DeviceBackend {
    val builder = DeviceBackendBuilder()
    block(builder)
    return builder.build()
}

/**
 * Same DSL as [deviceBackend] but the declared return type is [SteppedBackend]. [block]
 * **must** declare `onStep`; otherwise this function throws. Prefer this overload when
 * the caller needs the stepped-backend type statically (e.g. to pass into
 * `SimulationSession(connections = listOf(...))`).
 */
public fun steppedBackend(block: DeviceBackendBuilder.() -> Unit): SteppedBackend {
    val backend = deviceBackend(block)
    return backend as? SteppedBackend
        ?: error("steppedBackend { } requires an onStep { } block; use deviceBackend { } for state-less backends")
}

