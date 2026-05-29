package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.time.Duration
import kotlin.time.Clock

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
        val common = BuiltCommon(
            readers = readers.toMap(),
            observedReaders = observedReaders.toMap(),
            binaryReaders = binaryReaders.toMap(),
            writers = writers.toMap(),
            actions = actions.toMap(),
            batchObservedReadBody = batchObservedReadBody,
            batchBinaryReadBody = batchBinaryReadBody,
            batchWriteBody = batchWriteBody,
            closeBody = closeBlock,
        )
        val step = stepBlock
        return if (step != null) BuiltSteppedBackend(common, step) else BuiltDeviceBackend(common)
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

/** Shared read / write / execute / close state for both built backends. */
private class BuiltCommon(
    val readers: Map<Name, Pair<suspend () -> Any?, MetaConverter<*>>>,
    val observedReaders: Map<Name, Pair<suspend () -> ObservedValue<Any?>, MetaConverter<*>>>,
    val binaryReaders: Map<Name, suspend () -> Binary>,
    val writers: Map<Name, suspend (Meta) -> OperationOutcome<Unit>>,
    val actions: Map<Name, suspend (Meta?) -> Meta?>,
    val batchObservedReadBody:
            (suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>)?,
    val batchBinaryReadBody:
            (suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>)?,
    val batchWriteBody:
            (suspend DeviceEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>)?,
    val closeBody: (() -> Unit)?,
) {
    suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
        readers[property.name]?.let { (reader, converter) ->
            return runCatchingOperation {
                converter.convertAny(reader())
            }
        }
        observedReaders[property.name]?.let { (reader, converter) ->
            return runCatchingOperation {
                converter.convertAny(reader().value)
            }
        }
        return backendFault(OperationFaultTypes.UnknownProperty, "Unknown property '${property.name}' on device backend")
    }

    suspend fun readObserved(property: PropertyDescriptor, clock: Clock): OperationOutcome<ObservedValue<Meta?>> {
        observedReaders[property.name]?.let { (reader, converter) ->
            return runCatchingOperation {
                val observed = reader()
                val meta = converter.convertAny(observed.value)
                ObservedValue(value = meta, time = observed.time, quality = observed.quality)
            }
        }
        return when (val outcome = read(property)) {
            is OperationOutcome.Ok -> OperationOutcome.Ok(
                ObservedValue(value = outcome.value, time = clock.now(), quality = DataQuality.GOOD),
            )
            is OperationOutcome.Fail -> outcome
        }
    }

    suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> {
        binaryReaders[property.name]?.let { reader ->
            return runCatchingOperation { reader() }
        }
        return backendFault(OperationFaultTypes.UnsupportedValue, "Property '${property.name}' has no binary reader")
    }

    suspend fun readBatchObserved(
        device: DeviceEnvironment,
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        batchObservedReadBody?.let { return it.invoke(device, properties) }
        return properties.associate { property -> property.name to readObserved(property, device.clock) }
    }

    suspend fun readBatchBinary(
        device: DeviceEnvironment,
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> {
        batchBinaryReadBody?.let { return it.invoke(device, properties) }
        return properties.associate { property -> property.name to readBinary(property) }
    }

    suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
        val writer = writers[property.name]
            ?: return validationFault("Property '${property.name}' is not writable on device backend")
        return writer(value)
    }

    suspend fun writeBatch(
        device: DeviceEnvironment,
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> {
        batchWriteBody?.let { return it.invoke(device, values) }
        val results = LinkedHashMap<Name, OperationOutcome<Unit>>()
        for ((property, value) in values) {
            results[property.name] = write(property, value)
        }
        return results
    }

    suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> {
        val body = actions[action.name]
            ?: return backendFault(OperationFaultTypes.UnknownAction, "Unknown action '${action.name}' on device backend")
        return runCatchingOperation {
            body(argument)
        }
    }

    fun close() {
        closeBody?.invoke()
    }
}

private fun backendFault(type: Name, message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(GenericOperationFault(faultType = type, message = message))

private fun validationFault(message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            details = faultDetails(message),
        ),
    )

@Suppress("UNCHECKED_CAST")
private fun MetaConverter<*>.convertAny(value: Any?): Meta =
    (this as MetaConverter<Any?>).convert(value)

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltDeviceBackend(private val c: BuiltCommon) : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun readObserved(property: PropertyDescriptor): OperationOutcome<ObservedValue<Meta?>> =
        c.readObserved(property, device.clock)
    context(device: DeviceEnvironment)
    override suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> = c.readBinary(property)
    context(device: DeviceEnvironment)
    override suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> = c.readBatchObserved(device, properties)
    context(device: DeviceEnvironment)
    override suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> = c.readBatchBinary(device, properties)
    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> = c.writeBatch(device, values)
    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltSteppedBackend(
    private val c: BuiltCommon,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend {
    override fun step(dt: Duration) { stepBody(dt) }
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun readObserved(property: PropertyDescriptor): OperationOutcome<ObservedValue<Meta?>> =
        c.readObserved(property, device.clock)
    context(device: DeviceEnvironment)
    override suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> = c.readBinary(property)
    context(device: DeviceEnvironment)
    override suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> = c.readBatchObserved(device, properties)
    context(device: DeviceEnvironment)
    override suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> = c.readBatchBinary(device, properties)
    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> = c.writeBatch(device, values)
    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}
