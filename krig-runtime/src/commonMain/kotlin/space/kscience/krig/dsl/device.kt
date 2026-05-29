package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.operationAttributes
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.DeviceEnvironment
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
import kotlinx.coroutines.CoroutineScope
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
 * a compile-time error, which is the whole point of the split form.
 *
 * ```kotlin
 * val reactor = device("reactor", ReactorConnection(protocolEngine), productionContext) {
 *     manifest(ReactorManifest)
 *     install(Caching) { defaultTtl = 100.milliseconds }
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
 * Shared read / write / execute / close state for the device DSL backends.
 */
internal class DeclarativeBackendCore(
    val readers: Map<Name, DeviceReadBlock<Any>>,
    val writers: Map<Name, DeviceWriteBlock>,
    val valueWriters: Map<Name, (Any?) -> Unit>,
    val actions: Map<Name, DeviceActionBlock>,
    val closeBody: (() -> Unit)?,
) {
    private val scopeLock = SynchronizedObject()
    private var cachedScope: DeclarativeScope? = null

    context(device: DeviceEnvironment)
    private fun scope(): DeclarativeScope {
        cachedScope?.takeIf { it.environment === device }?.let { return it }
        return synchronized(scopeLock) {
            cachedScope?.takeIf { it.environment === device }
                ?: DeclarativeScope(this@DeclarativeBackendCore, device).also { cachedScope = it }
        }
    }

    context(device: DeviceEnvironment)
    suspend fun readValue(name: Name): OperationOutcome<Any> {
        val reader = readers[name]
            ?: return unknownDeclarativeOperation(OperationFaultTypes.UnknownProperty, "Unknown property '$name' on Device DSL backend")
        return runCatchingOperation {
            val scope = scope()
            with(reader) { scope.read() }
        }
    }

    context(device: DeviceEnvironment)
    suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
        when (val outcome = readValue(property.name)) {
            is OperationOutcome.Fail -> outcome
            is OperationOutcome.Ok -> runCatchingOperation {
                when (val value = outcome.value) {
                    is Meta -> value
                    is Double -> metaOf(value)
                    is Int -> metaOf(value)
                    is Long -> metaOf(value)
                    is Boolean -> metaOf(value)
                    is String -> metaOf(value)
                    is MetaRepr -> value.toMeta()
                    else -> unsupportedDeclarativeValue(property.name, value)
                }
            }
        }

    context(device: DeviceEnvironment)
    suspend fun writeValue(name: Name, value: Any?, toMeta: (Any?) -> Meta): OperationOutcome<Unit> {
        val directWriter = valueWriters[name]
        if (directWriter != null) {
            return try {
                directWriter(value)
                OperationOutcome.OkUnit
            } catch (e: ClassCastException) {
                writeTypeError(name, value, e)
            } catch (e: IllegalArgumentException) {
                writeTypeError(name, value, e)
            }
        }
        val writer = writers[name]
            ?: return validationFault(name, "Property '$name' is not writable on Device DSL backend")
        val meta = try {
            toMeta(value)
        } catch (e: ClassCastException) {
            return writeTypeError(name, value, e)
        } catch (e: IllegalArgumentException) {
            return writeTypeError(name, value, e)
        }
        return runCatchingOperation {
            val scope = scope()
            with(writer) { scope.write(meta) }
        }
    }

    context(device: DeviceEnvironment)
    suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
        val writer = writers[property.name]
            ?: return validationFault(property.name, "Property '${property.name}' is not writable on Device DSL backend")
        return runCatchingOperation {
            val scope = scope()
            with(writer) { scope.write(value) }
        }
    }

    context(device: DeviceEnvironment)
    suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> {
        val body = actions[action.name]
            ?: return unknownDeclarativeOperation(
                OperationFaultTypes.UnknownAction,
                "Unknown action '${action.name}' on Device DSL backend",
            )
        return runCatchingOperation {
            val scope = scope()
            with(body) { scope.execute(argument) }
        }
    }

    fun close() { closeBody?.invoke() }
}

private class DeclarativeScope(
    private val core: DeclarativeBackendCore,
    val environment: DeviceEnvironment,
) : DeviceActionScope {
    override val clock get() = environment.clock
    override val deviceScope: CoroutineScope get() = environment.deviceScope
    override val name: Name get() = environment.name

    override suspend fun readProperty(name: Name): Meta =
        context(environment) {
            core.read(synthesizeProperty(name, mutable = core.writers.containsKey(name))).getOrThrow()
        }

    override suspend fun <T> read(spec: DevicePropertyContract<T>): T =
        context(environment) {
            val value = core.readValue(spec.name).getOrThrow()
            @Suppress("UNCHECKED_CAST")
            when (value) {
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
        context(environment) {
            core.write(synthesizeProperty(name, mutable = true), value).getOrThrow()
        }
    }

    override suspend fun <T> write(spec: MutableDevicePropertyContract<T>, value: T) {
        context(environment) {
            core.writeValue(spec.name, value) { raw ->
                @Suppress("UNCHECKED_CAST")
                spec.converter.convert(raw as T)
            }.getOrThrow()
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
        context(environment) {
            core.execute(ActionDescriptor(action), argument).getOrThrow()
        }

    override suspend fun <I, O> execute(spec: DeviceActionContract<I, O>, input: I): O? {
        val result = execute(spec.name, spec.inputConverter.convert(input))
        return result?.let(spec.outputConverter::read)
    }

    private suspend fun <T : Any> readDeclarativeValue(name: Name, expected: String, decode: (Any) -> T?): T =
        context(environment) {
            val value = core.readValue(name).getOrThrow()
            decode(value) ?: readTypeError(name, expected, value)
        }

    private suspend fun writeDeclarativeValue(name: Name, value: Any, toMeta: () -> Meta) {
        context(environment) {
            core.writeValue(name, value) { toMeta() }.getOrThrow()
        }
    }
}

/** Device DSL backend without time-advancement. */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
internal class DeclarativeDeviceBackend(private val c: DeclarativeBackendCore) : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(
        property: PropertyDescriptor,
        value: Meta,
    ): OperationOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(
        action: ActionDescriptor,
        argument: Meta?,
    ): OperationOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

/** Device DSL backend used when `onStep` was declared. */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
internal class DeclarativeSteppedBackend(
    private val c: DeclarativeBackendCore,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend {
    override fun step(dt: Duration) { stepBody(dt) }
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(
        property: PropertyDescriptor,
        value: Meta,
    ): OperationOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(
        action: ActionDescriptor,
        argument: Meta?,
    ): OperationOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

/** Picks the concrete class based on whether `onStep` was declared. */
internal fun declarativeBackend(
    readers: Map<Name, DeviceReadBlock<Any>>,
    writers: Map<Name, DeviceWriteBlock>,
    valueWriters: Map<Name, (Any?) -> Unit>,
    actions: Map<Name, DeviceActionBlock>,
    stepBody: ((Duration) -> Unit)?,
    closeBody: (() -> Unit)?,
): DeviceBackend {
    val core = DeclarativeBackendCore(readers, writers, valueWriters, actions, closeBody)
    return if (stepBody != null) DeclarativeSteppedBackend(core, stepBody) else DeclarativeDeviceBackend(core)
}

internal fun synthesizeProperty(name: Name, mutable: Boolean): PropertyDescriptor = PropertyDescriptor(
    name = name,
    kind = PropertyKind.LOGICAL,
    valueTypeId = TypeIds.META,
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

private fun unknownDeclarativeOperation(type: Name, message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(GenericOperationFault(faultType = type, message = message))

private fun validationFault(property: Name, message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            details = Meta {
                OperationFaultDetails.PROPERTY put property.toString()
                OperationFaultDetails.MESSAGE put message
            },
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

private fun writeTypeError(property: Name, value: Any?, cause: Exception): OperationOutcome.Fail =
    OperationOutcome.Fail(writeFault(property, value, cause))

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

/** Shorthand for an action that returns no result. Equivalent to `null`. */
@Suppress("SameReturnValue")
public fun okUnit(): Meta? = null

/** Shorthand for an action that succeeds with a [Meta] payload. */
public fun ok(value: Meta): Meta = value

/** Shorthand for an action that succeeds with a numeric result. */
public fun ok(value: Number): Meta? = when (value) {
    is Double -> metaOf(value)
    is Int -> metaOf(value)
    is Long -> metaOf(value)
    else -> metaOf(value.toDouble())
}

/** Shorthand for an action that succeeds with a string result. */
public fun ok(value: String): Meta = metaOf(value)

/** Shorthand for an action that succeeds with a boolean result. */
public fun ok(value: Boolean): Meta = metaOf(value)
