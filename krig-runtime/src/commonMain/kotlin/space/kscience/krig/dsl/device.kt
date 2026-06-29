package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.operationAttributes
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.validationFault
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.SteppedBackend
import space.kscience.krig.core.contracts.booleanValue
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.intValue
import space.kscience.krig.core.contracts.longValue
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration

/**
 * Builds a device via declarative `property` / `action` / `onStep` helpers.
 *
 * ```kotlin
 * val thermo = device("thermo") {
 *     property("temperature") { readSensor(name) }
 *     mutableProperty("setpoint", initial = 20.0)
 *     action("reset") { _ -> counter = 0; metaOf(0) }
 * }
 * ```
 */
public suspend fun device(
    name: Name,
    context: Context = scriptContext(),
    builder: DeclarativeDeviceBuilder.() -> Unit,
): Device = DeclarativeDeviceBuilder(name, context).apply(builder).build()

/** [device] form with an explicit [DeviceRuntime] (for virtual clocks/time sources). */
public suspend fun device(
    name: Name,
    runtime: DeviceRuntime,
    builder: DeclarativeDeviceBuilder.() -> Unit,
): Device = DeclarativeDeviceBuilder(name, runtime).apply(builder).build()

/** String-name overload of the [device] form. */
public suspend fun device(
    name: String,
    context: Context = scriptContext(),
    builder: DeclarativeDeviceBuilder.() -> Unit,
): Device = device(name.asName(), context, builder)

/** String-name overload of the explicit-runtime [device] form. */
public suspend fun device(
    name: String,
    runtime: DeviceRuntime,
    builder: DeclarativeDeviceBuilder.() -> Unit,
): Device = device(name.asName(), runtime, builder)

/**
 * Builds a device from a pre-constructed [DeviceBackend] (protocol adapter, physics
 * simulation, Wasm/FMI slave). Inside [builder] only PipelineFeatureSpec installation and
 * descriptor sources are available — `property` / `action` declarations are
 * a compile-time error, so declarations stay in the explicit backend.
 *
 * ```kotlin
 * val reactor = device("reactor", ReactorConnection(protocolEngine), productionContext) {
 *     manifest(ReactorManifest)
 * }
 * ```
 */
public suspend fun device(
    name: Name,
    backend: DeviceBackend,
    context: Context = scriptContext(),
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = ExplicitDeviceBuilder(name, context, backend).apply(builder).build()

/** Explicit-backend [device] form with an explicit [DeviceRuntime]. */
public suspend fun device(
    name: Name,
    backend: DeviceBackend,
    runtime: DeviceRuntime,
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = ExplicitDeviceBuilder(name, runtime, backend).apply(builder).build()

/** String-name overload of the explicit-backend [device] form. */
public suspend fun device(
    name: String,
    backend: DeviceBackend,
    context: Context = scriptContext(),
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = device(name.asName(), backend, context, builder)

/** String-name overload of the explicit-runtime explicit-backend [device] form. */
public suspend fun device(
    name: String,
    backend: DeviceBackend,
    runtime: DeviceRuntime,
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = device(name.asName(), backend, runtime, builder)

/**
 * Session-aware [device] form — the Context comes from the ambient [DeviceRuntime]
 * context parameter so nested DSL blocks don't repeat the context argument. Kotlin 2.4
 * `context(...)` semantics: works with any call site that already binds a DeviceRuntime
 * (e.g. inside `deviceGroup { ... }`).
 */
context(session: DeviceRuntime)
public suspend fun device(
    name: Name,
    builder: DeclarativeDeviceBuilder.() -> Unit,
): Device = device(name, session.context, builder)

/** Session-aware explicit-backend [device] form. */
context(session: DeviceRuntime)
public suspend fun device(
    name: Name,
    backend: DeviceBackend,
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = device(name, backend, session.context, builder)

// ── Private impl: synthesised backend for DeclarativeDeviceBuilder ──

/**
 * Device DSL backend: a re-entrant interpreter whose property/action blocks run inside a
 * [DeclarativeScope] that can read and write sibling members. It implements [DeviceBackend]
 * directly; the Meta control plane lives here because the scope and raw-value path are intrinsic
 * to the DSL and cannot be lowered into the contract-level [BackendCore].
 */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
internal class DeclarativeBackendCore(
    val readers: Map<Name, DeviceReadBlock<Any>>,
    val writers: Map<Name, DeviceWriteBlock>,
    val valueWriters: Map<Name, (Any?) -> Unit>,
    val actions: Map<Name, DeviceActionBlock>,
    val observedReaders: Map<Name, DeviceObservedReadBlock>,
    val closeBody: (() -> Unit)?,
) : DeviceBackend {

    // Routes a DSL scope's sibling read/write through the assembled operation pipeline (timeouts,
    // resource locks, gates) instead of straight to the backend, so a computed property reading a
    // contract-backed sibling honours that contract's QoS. Wired once after assembly; returns `null`
    // (read) / `false` (write) for properties without a registered contract, so schema-less members
    // keep the direct path. Re-entrant calls are safe: held resource locks are skipped, not re-acquired.
    internal var pipelineRead: (suspend (Name) -> Meta?)? = null
    internal var pipelineWrite: (suspend (Name, Meta) -> Boolean)? = null

    override fun bind(environment: BackendEnvironment): BoundDeviceBackend =
        BoundDeclarativeBackend(environment)

    override fun close() { closeBody?.invoke() }

    internal inner class BoundDeclarativeBackend(
        override val environment: BackendEnvironment,
    ) : BoundDeviceBackend {
        private val scopeLock = SynchronizedObject()
        private var cachedScope: DeclarativeScope? = null

        private fun scope(): DeclarativeScope {
            cachedScope?.let { return it }
            return synchronized(scopeLock) {
                cachedScope ?: DeclarativeScope(this@DeclarativeBackendCore, this).also { cachedScope = it }
            }
        }

        suspend fun readValue(name: Name): Any {
            val reader = readers[name]
                ?: throw OperationFaultException(
                    GenericOperationFault(
                        OperationFaultTypes.UnknownProperty,
                        "Unknown property '$name' on Device DSL backend",
                    ),
                )
            return with(reader) { scope().read() }
        }

        override suspend fun read(property: PropertyDescriptor): Meta =
            declarativeValueToMeta(property.name, readValue(property.name))

        /**
         * Quality-aware read for properties declared via `observedProperty(name) { ObservedValue(...) }`.
         */
        override suspend fun readObserved(property: PropertyDescriptor): ObservedValue<Meta?> {
            val block = observedReaders[property.name] ?: return super.readObserved(property)
            val observed = with(block) { scope().read() }
            return observed.map { raw -> raw?.let { declarativeValueToMeta(property.name, it) } }
        }

        suspend fun writeValue(name: Name, value: Any?, toMeta: (Any?) -> Meta) {
            val directWriter = valueWriters[name]
            if (directWriter != null) {
                try {
                    directWriter(value)
                    return
                } catch (e: ClassCastException) {
                    writeTypeError(name, value, e)
                } catch (e: IllegalArgumentException) {
                    writeTypeError(name, value, e)
                }
            }
            val writer = writers[name]
                ?: throw OperationFaultException(
                    validationFault("Property '$name' is not writable on Device DSL backend", property = name).fault,
                )
            val meta = try {
                toMeta(value)
            } catch (e: ClassCastException) {
                writeTypeError(name, value, e)
            } catch (e: IllegalArgumentException) {
                writeTypeError(name, value, e)
            }
            with(writer) { scope().write(meta) }
        }

        override suspend fun write(property: PropertyDescriptor, value: Meta) {
            val writer = writers[property.name]
                ?: throw OperationFaultException(
                    validationFault(
                        "Property '${property.name}' is not writable on Device DSL backend",
                        property = property.name,
                    ).fault,
                )
            with(writer) { scope().write(value) }
        }

        override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta? {
            val body = actions[action.name]
                ?: throw OperationFaultException(
                    GenericOperationFault(
                        OperationFaultTypes.UnknownAction,
                        "Unknown action '${action.name}' on Device DSL backend",
                    ),
                )
            return with(body) { scope().execute(argument) }
        }

        override fun close() { closeBody?.invoke() }
    }
}

private class DeclarativeScope(
    private val core: DeclarativeBackendCore,
    private val backend: DeclarativeBackendCore.BoundDeclarativeBackend,
) : DeviceActionScope {
    val environment: BackendEnvironment get() = backend.environment
    override val clock get() = environment.clock
    override val timeSource get() = environment.timeSource
    override val name: Name get() = environment.name

    override suspend fun readProperty(name: Name): Meta =
        core.pipelineRead?.invoke(name)
            ?: backend.read(synthesizeProperty(name, mutable = core.writers.containsKey(name)))

    override suspend fun <T> read(spec: DevicePropertyContract<T>): T {
        core.pipelineRead?.invoke(spec.name)?.let { return spec.converter.read(it) }
        val value = backend.readValue(spec.name)
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is Meta -> spec.converter.read(value)
            else -> value as T
        }
    }

    override suspend fun readDouble(name: Name): Double =
        readDeclarativeValue(name, "Double") { value ->
            when (value) {
                is Double -> value
                is Meta -> value.doubleValue
                else -> null
            }
        }

    override suspend fun readInt(name: Name): Int =
        readDeclarativeValue(name, "Int") { value ->
            when (value) {
                is Int -> value
                is Meta -> value.intValue
                else -> null
            }
        }

    override suspend fun readLong(name: Name): Long =
        readDeclarativeValue(name, "Long") { value ->
            when (value) {
                is Long -> value
                is Meta -> value.longValue
                else -> null
            }
        }

    override suspend fun readBoolean(name: Name): Boolean =
        readDeclarativeValue(name, "Boolean") { value ->
            when (value) {
                is Boolean -> value
                is Meta -> value.booleanValue
                else -> null
            }
        }

    override suspend fun readString(name: Name): String =
        readDeclarativeValue(name, "String") { value ->
            when (value) {
                is String -> value
                is Meta -> value.stringValue
                else -> null
            }
        }

    override suspend fun writeProperty(name: Name, value: Meta) {
        val write = core.pipelineWrite
        if (write != null && write(name, value)) return
        backend.write(synthesizeProperty(name, mutable = true), value)
    }

    override suspend fun <T> write(spec: MutableDevicePropertyContract<T>, value: T) {
        val routed = core.pipelineWrite
        if (routed != null && routed(spec.name, spec.converter.convert(value))) return
        backend.writeValue(spec.name, value) { raw ->
            @Suppress("UNCHECKED_CAST")
            spec.converter.convert(raw as T)
        }
    }

    override suspend fun writeDouble(name: Name, value: Double): Unit =
        writeDeclarativeValue(name, value) { metaOf(value) }

    override suspend fun writeInt(name: Name, value: Int): Unit =
        writeDeclarativeValue(name, value) { metaOf(value) }

    override suspend fun writeLong(name: Name, value: Long): Unit =
        writeDeclarativeValue(name, value) { metaOf(value) }

    override suspend fun writeBoolean(name: Name, value: Boolean): Unit =
        writeDeclarativeValue(name, value) { metaOf(value) }

    override suspend fun writeString(name: Name, value: String): Unit =
        writeDeclarativeValue(name, value) { metaOf(value) }

    override suspend fun execute(action: Name, argument: Meta?): Meta? =
        backend.execute(ActionDescriptor(action), argument)

    override suspend fun <I, O> execute(spec: DeviceActionContract<I, O>, input: I): O? {
        val result = execute(spec.name, spec.inputConverter.convert(input))
        return result?.let(spec.outputConverter::read)
    }

    private suspend fun <T : Any> readDeclarativeValue(name: Name, expected: String, decode: (Any) -> T?): T {
        val routed: Any? = core.pipelineRead?.invoke(name)
        if (routed != null) return decode(routed) ?: readTypeError(name, expected, routed)
        val value = backend.readValue(name)
        return decode(value) ?: readTypeError(name, expected, value)
    }

    private suspend fun writeDeclarativeValue(name: Name, value: Any, toMeta: () -> Meta) {
        val write = core.pipelineWrite
        if (write != null && write(name, toMeta())) return
        backend.writeValue(name, value) { toMeta() }
    }
}

/** Builds the device DSL backend, attaching a step body via [SteppedBackend] when `onStep` was declared. */
internal fun declarativeBackend(
    readers: Map<Name, DeviceReadBlock<Any>>,
    writers: Map<Name, DeviceWriteBlock>,
    valueWriters: Map<Name, (Any?) -> Unit>,
    actions: Map<Name, DeviceActionBlock>,
    observedReaders: Map<Name, DeviceObservedReadBlock> = emptyMap(),
    stepBody: ((Duration) -> Unit)?,
    closeBody: (() -> Unit)?,
): DeviceBackend {
    val core = DeclarativeBackendCore(readers, writers, valueWriters, actions, observedReaders, closeBody)
    return if (stepBody != null) SteppedBackend(core, stepBody) else core
}

internal fun synthesizeProperty(
    name: Name,
    mutable: Boolean,
    valueTypeId: TypeId = TypeIds.META,
): PropertyDescriptor = PropertyDescriptor(
    name = name,
    kind = PropertyKind.LOGICAL,
    valueTypeId = valueTypeId,
    metaDescriptor = MetaDescriptor(),
    attributes = operationAttributes {
        OperationAttributeKeys.Access(AccessAttribute(readable = true, mutable = mutable))
    },
)

internal fun writeTypeError(property: String, expected: String, got: Meta): Nothing =
    throw OperationFaultException(
        ValidationFault(
            details = Meta {
                OperationFaultDetails.PROPERTY put property
                OperationFaultDetails.EXPECTED_TYPE put expected
                OperationFaultDetails.MESSAGE put "Property '$property' write requires a scalar $expected value."
                "actual" put got.toString()
            },
        ),
    )

/** Maps a non-null declarative value to [Meta], shared by the value path and the observed path. */
private fun declarativeValueToMeta(property: Name, value: Any): Meta =
    when (value) {
        is Meta -> value
        is Double -> metaOf(value)
        is Int -> metaOf(value)
        is Long -> metaOf(value)
        is Boolean -> metaOf(value)
        is String -> metaOf(value)
        is MetaRepr -> value.toMeta()
        else -> unsupportedDeclarativeValue(property, value)
    }

/**
 * The value-only read path cannot represent absence, so an observed property whose block yields a
 * `null` value (e.g. unavailable, quality BAD) faults here; quality-aware callers use [DeclarativeBackendCore.readObserved].
 */
internal fun observedValueAbsent(property: Name, quality: DataQuality): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnsupportedValue,
            message = "Observed property '$property' produced no value on the value-only path (quality=$quality). " +
                    "Read it through readObserved to receive the null value with its quality.",
        ),
    )

private fun unsupportedDeclarativeValue(property: Name, value: Any): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnsupportedValue,
            message =
            "Unsupported property value type ${value::class.simpleName} for '$property' — " +
                    "expected Meta, Double, Int, Long, Boolean, String, or MetaRepr. " +
                    "For custom types, implement MetaRepr or use the explicit-backend form.",
        ),
    )

private fun readTypeError(property: Name, expected: String, got: Any): Nothing =
    throw OperationFaultException(
        ValidationFault(
            details = Meta {
                OperationFaultDetails.PROPERTY put property.toString()
                OperationFaultDetails.EXPECTED_TYPE put expected
                OperationFaultDetails.MESSAGE put "Property '$property' read requires a scalar $expected value."
                "actual" put (got::class.simpleName ?: got.toString())
            },
        ),
    )

private fun writeTypeError(property: Name, value: Any?, cause: Exception): Nothing =
    throw OperationFaultException(writeFault(property, value, cause))

private fun writeFault(property: Name, value: Any?, cause: Exception): ValidationFault =
    ValidationFault(
        details = Meta {
            OperationFaultDetails.PROPERTY put property.toString()
            OperationFaultDetails.MESSAGE put "Property '$property' write received a value incompatible with its typed spec."
            "actual" put (value?.let { it::class.simpleName ?: it.toString() } ?: "null")
            "cause" put (cause.message ?: cause::class.simpleName.orEmpty())
        },
    )

// ── DSL action return helpers (usable inside `device { action("x") { ... } }` blocks) ──
// Named noResult/metaResult on purpose: `ok`/`okUnit` would collide with
// space.kscience.krig.api.result.ok/okUnit (OperationOutcome constructors) under dual import.

/** Sentinel for an action that returns no result. Equivalent to `null`. */
public val noResult: Meta? = null

/** Shorthand for an action that succeeds with a [Meta] payload. */
public fun metaResult(value: Meta): Meta = value

/** Shorthand for an action that succeeds with a numeric result. */
public fun metaResult(value: Number): Meta? = when (value) {
    is Double -> metaOf(value)
    is Int -> metaOf(value)
    is Long -> metaOf(value)
    else -> metaOf(value.toDouble())
}

/** Shorthand for an action that succeeds with a string result. */
public fun metaResult(value: String): Meta = metaOf(value)

/** Shorthand for an action that succeeds with a boolean result. */
public fun metaResult(value: Boolean): Meta = metaOf(value)
