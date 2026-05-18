package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.runCatchingDevice
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
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

    private val readers: MutableMap<Name, Pair<() -> Any?, MetaConverter<*>>> = mutableMapOf()
    private val writers: MutableMap<Name, (Meta) -> DeviceOutcome<Unit>> = mutableMapOf()
    private val actions: MutableMap<Name, suspend (Meta?) -> Meta?> = mutableMapOf()
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
        var cell = initial
        readers[key] = Pair({ cell }, converter)
        writers[key] = { meta ->
            val decoded = converter.readOrNull(meta)
            if (decoded == null) {
                validationFault("Property '$key': cannot decode Meta to ${converter::class.simpleName}")
            } else {
                cell = decoded
                DeviceOutcome.OkUnit
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
        readers[key] = Pair({ compute() }, MetaConverter.double)
        return ConnectionProperty(key, compute, null)
    }

    /**
     * Declares an action that takes a [Meta] argument and returns a [Meta]
     * result. The body is suspendable so it can perform I/O against
     * downstream connections if needed.
     */
    public fun action(name: String, body: suspend (argument: Meta?) -> Meta?) {
        actions[name.asName()] = body
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

    /** Action keyed by a typed [DeviceActionContract]. */
    public fun action(spec: DeviceActionContract<*, *>, body: suspend (argument: Meta?) -> Meta?) {
        actions[spec.name] = body
    }

    /**
     * Declares the step body -- invoked by the orchestrator with the canonical
     * `dt` for the current tick. May read and write declared properties via
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
            writers = writers.toMap(),
            actions = actions.toMap(),
            closeBody = closeBlock,
        )
        val step = stepBlock
        return if (step != null) BuiltSteppedBackend(common, step) else BuiltDeviceBackend(common)
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
    val readers: Map<Name, Pair<() -> Any?, MetaConverter<*>>>,
    val writers: Map<Name, (Meta) -> DeviceOutcome<Unit>>,
    val actions: Map<Name, suspend (Meta?) -> Meta?>,
    val closeBody: (() -> Unit)?,
) {
    fun read(property: PropertyDescriptor): DeviceOutcome<Meta> {
        val (reader, converter) = readers[property.name]
            ?: return backendFault("UNKNOWN_PROPERTY", "Unknown property '${property.name}' on device backend")
        return runCatchingDevice {
            @Suppress("UNCHECKED_CAST")
            (converter as MetaConverter<Any?>).convert(reader())
        }
    }

    fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> {
        val writer = writers[property.name]
            ?: return backendFault("PROPERTY_NOT_WRITABLE", "Property '${property.name}' is not writable on device backend")
        return writer(value)
    }

    suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> {
        val body = actions[action.name]
            ?: return backendFault("UNKNOWN_ACTION", "Unknown action '${action.name}' on device backend")
        return runCatchingDevice {
            body(argument)
        }
    }

    fun close() {
        closeBody?.invoke()
    }
}

private fun backendFault(code: String, message: String): DeviceOutcome.Fail =
    DeviceOutcome.Fail(GenericDeviceFault(code = code, message = message))

private fun validationFault(message: String): DeviceOutcome.Fail =
    DeviceOutcome.Fail(
        ValidationFault(
            details = Meta { "message" put message },
        ),
    )

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltDeviceBackend(private val c: BuiltCommon) : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

@OptIn(UnstableKrigForSubclassing::class)
private class BuiltSteppedBackend(
    private val c: BuiltCommon,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend {
    override fun step(dt: Duration) { stepBody(dt) }
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}
