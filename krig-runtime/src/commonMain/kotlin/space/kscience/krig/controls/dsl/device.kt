package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.runCatchingDevice
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
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
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
 * Builds a device inline via declarative `property` / `action` / `onStep` helpers.
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
    builder: InlineDeviceBuilder.() -> Unit,
): Device = InlineDeviceBuilder(name, context).apply(builder).build()

/** Inline [device] form with an explicit [DeviceRuntime] (for virtual clocks/time sources). */
public suspend fun device(
    name: Name,
    runtime: DeviceRuntime,
    builder: InlineDeviceBuilder.() -> Unit,
): Device = InlineDeviceBuilder(name, runtime).apply(builder).build()

/** String-name overload of the inline [device] form. */
public suspend fun device(
    name: String,
    context: Context = scriptContext(),
    builder: InlineDeviceBuilder.() -> Unit,
): Device = device(name.asName(), context, builder)

/** String-name overload of the explicit-runtime inline [device] form. */
public suspend fun device(
    name: String,
    runtime: DeviceRuntime,
    builder: InlineDeviceBuilder.() -> Unit,
): Device = device(name.asName(), runtime, builder)

/**
 * Builds a device from a pre-constructed [DeviceBackend] (protocol adapter, physics
 * simulation, Wasm/FMI slave). Inside [builder] only DeviceFeatureSpec installation and
 * descriptor sources are available — inline `property` / `action` declarations are
 * a compile-time error, which is the whole point of the split form.
 *
 * ```kotlin
 * val reactor = device("reactor", ReactorConnection(protocolEngine), productionContext) {
 *     blueprint(ReactorBlueprint)
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
 * Session-aware inline [device] form — the Context comes from the ambient [DeviceRuntime]
 * context parameter so nested DSL blocks don't repeat the context argument. Kotlin 2.4
 * `context(...)` semantics: works with any call site that already binds a DeviceRuntime
 * (e.g. inside `deviceGroup { ... }`).
 */
context(session: DeviceRuntime)
public suspend fun device(
    name: Name,
    builder: InlineDeviceBuilder.() -> Unit,
): Device = InlineDeviceBuilder(name, session.context).apply(builder).build()

/** Session-aware explicit-backend [device] form. */
context(session: DeviceRuntime)
public suspend fun device(
    name: Name,
    backend: DeviceBackend,
    builder: ExplicitDeviceBuilder.() -> Unit = {},
): Device = ExplicitDeviceBuilder(name, session.context, backend).apply(builder).build()

// ── Private impl: synthesised backend for InlineDeviceBuilder ──

/**
 * Shared read / write / execute / close state for the inline-DSL backends.
 */
internal class InlineBackendCore(
    val readers: Map<Name, DeviceReadBlock<Any>>,
    val writers: Map<Name, DeviceWriteBlock>,
    val valueWriters: Map<Name, (Any?) -> Unit>,
    val actions: Map<Name, DeviceActionBlock>,
    val closeBody: (() -> Unit)?,
) {
    private val scopeLock = SynchronizedObject()
    private var cachedScope: InlineScope? = null

    context(device: DeviceEnvironment)
    private fun scope(): InlineScope {
        cachedScope?.takeIf { it.environment === device }?.let { return it }
        return synchronized(scopeLock) {
            cachedScope?.takeIf { it.environment === device }
                ?: InlineScope(this@InlineBackendCore, device).also { cachedScope = it }
        }
    }

    context(device: DeviceEnvironment)
    suspend fun readValue(name: Name): DeviceOutcome<Any> = runCatchingDevice {
        val reader = readers[name]
            ?: unknownInlineMember("UNKNOWN_PROPERTY", "Unknown property '$name' on inline device backend")
        val scope = scope()
        with(reader) { scope.read() }
    }

    context(device: DeviceEnvironment)
    suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = runCatchingDevice {
        when (val value = readValue(property.name).getOrThrow()) {
            is Meta -> value
            is Double -> metaOf(value)
            is Int -> metaOf(value)
            is Long -> metaOf(value)
            is Boolean -> metaOf(value)
            is String -> metaOf(value)
            is MetaRepr -> value.toMeta()
            else -> unknownInlineMember(
                code = "UNSUPPORTED_PROPERTY_VALUE",
                message =
                "Unsupported property value type ${value::class.simpleName} for '${property.name}' — " +
                        "expected Meta, Double, Int, Long, Boolean, String, or MetaRepr. " +
                        "For custom types, implement MetaRepr or use the explicit-backend form.",
            )
        }
    }

    context(device: DeviceEnvironment)
    suspend fun writeValue(name: Name, value: Any?, toMeta: (Any?) -> Meta): DeviceOutcome<Unit> =
        runCatchingDevice {
            val directWriter = valueWriters[name]
            if (directWriter != null) {
                try {
                    directWriter(value)
                } catch (e: ClassCastException) {
                    inlineWriteTypeError(name, value, e)
                } catch (e: IllegalArgumentException) {
                    inlineWriteTypeError(name, value, e)
                }
                return@runCatchingDevice
            }
            val writer = writers[name]
                ?: unknownInlineMember("PROPERTY_NOT_WRITABLE", "Property '$name' is not writable on inline device backend")
            val scope = scope()
            val meta = try {
                toMeta(value)
            } catch (e: ClassCastException) {
                inlineWriteTypeError(name, value, e)
            } catch (e: IllegalArgumentException) {
                inlineWriteTypeError(name, value, e)
            }
            with(writer) { scope.write(meta) }
        }

    context(device: DeviceEnvironment)
    suspend fun write(property: PropertyDescriptor, value: Meta): DeviceOutcome<Unit> =
        runCatchingDevice {
            val writer = writers[property.name]
                ?: unknownInlineMember("PROPERTY_NOT_WRITABLE", "Property '${property.name}' is not writable on inline device backend")
            val scope = scope()
            with(writer) { scope.write(value) }
        }

    context(device: DeviceEnvironment)
    suspend fun execute(action: ActionDescriptor, argument: Meta?): DeviceOutcome<Meta?> =
        runCatchingDevice {
            val body = actions[action.name]
                ?: unknownInlineMember("UNKNOWN_ACTION", "Unknown action '${action.name}' on inline device backend")
            val scope = scope()
            with(body) { scope.execute(argument) }
        }

    fun close() { closeBody?.invoke() }
}

private class InlineScope(
    private val core: InlineBackendCore,
    val environment: DeviceEnvironment,
) : DeviceActionScope {
    override val clock get() = environment.clock
    override val deviceScope: CoroutineScope get() = environment.deviceScope
    override val name: Name get() = environment.name

    override suspend fun readProperty(name: Name): Meta =
        with(environment) {
            core.read(synthesizeProperty(name, mutable = core.writers.containsKey(name))).getOrThrow()
        }

    override suspend fun <T> read(spec: DevicePropertySpec<*, T>): T =
        with(environment) {
            val value = core.readValue(spec.name).getOrThrow()
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Meta -> spec.converter.read(value)
                else -> value as T
            }
        }

    override suspend fun readDouble(name: Name): Double =
        readInlineValue(name, "Double") { value ->
            when (value) {
                is Double -> value
                is Meta -> value.doubleValue
                else -> null
            }
        }

    override suspend fun readInt(name: Name): Int =
        readInlineValue(name, "Int") { value ->
            when (value) {
                is Int -> value
                is Meta -> value.intValue
                else -> null
            }
        }

    override suspend fun readLong(name: Name): Long =
        readInlineValue(name, "Long") { value ->
            when (value) {
                is Long -> value
                is Meta -> value.longValue
                else -> null
            }
        }

    override suspend fun readBoolean(name: Name): Boolean =
        readInlineValue(name, "Boolean") { value ->
            when (value) {
                is Boolean -> value
                is Meta -> value.booleanValue
                else -> null
            }
        }

    override suspend fun readString(name: Name): String =
        readInlineValue(name, "String") { value ->
            when (value) {
                is String -> value
                is Meta -> value.stringValue
                else -> null
            }
        }

    override suspend fun writeProperty(name: Name, value: Meta) {
        with(environment) {
            core.write(synthesizeProperty(name, mutable = true), value).getOrThrow()
        }
    }

    override suspend fun <T> write(spec: MutableDevicePropertySpec<*, T>, value: T) {
        with(environment) {
            core.writeValue(spec.name, value) { raw ->
                @Suppress("UNCHECKED_CAST")
                spec.converter.convert(raw as T)
            }.getOrThrow()
        }
    }

    override suspend fun writeDouble(name: Name, value: Double): Unit =
        writeInlineValue(name, value) { metaOf(value) }

    override suspend fun writeInt(name: Name, value: Int): Unit =
        writeInlineValue(name, value) { metaOf(value) }

    override suspend fun writeLong(name: Name, value: Long): Unit =
        writeInlineValue(name, value) { metaOf(value) }

    override suspend fun writeBoolean(name: Name, value: Boolean): Unit =
        writeInlineValue(name, value) { metaOf(value) }

    override suspend fun writeString(name: Name, value: String): Unit =
        writeInlineValue(name, value) { metaOf(value) }

    override suspend fun execute(action: Name, argument: Meta?): Meta? =
        with(environment) {
            core.execute(ActionDescriptor(action), argument).getOrThrow()
        }

    override suspend fun <I, O> execute(spec: DeviceActionSpec<*, I, O>, input: I): O? {
        val result = execute(spec.name, spec.inputConverter.convert(input))
        return result?.let(spec.outputConverter::read)
    }

    private suspend fun <T : Any> readInlineValue(name: Name, expected: String, decode: (Any) -> T?): T =
        with(environment) {
            val value = core.readValue(name).getOrThrow()
            decode(value) ?: inlineTypeError(name, expected, value)
        }

    private suspend fun writeInlineValue(name: Name, value: Any, toMeta: () -> Meta) {
        with(environment) {
            core.writeValue(name, value) { toMeta() }.getOrThrow()
        }
    }
}

/** Inline builder's default backend class (no time-advancement). */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
internal class InlineDeviceBackend(private val c: InlineBackendCore) : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(
        property: PropertyDescriptor,
        value: Meta,
    ): DeviceOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(
        action: ActionDescriptor,
        argument: Meta?,
    ): DeviceOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

/** Inline builder's backend when `onStep` was declared — implements [SteppedBackend]. */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
internal class InlineSteppedBackend(
    private val c: InlineBackendCore,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend {
    override fun step(dt: Duration) { stepBody(dt) }
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): DeviceOutcome<Meta> = c.read(property)
    context(device: DeviceEnvironment)
    override suspend fun write(
        property: PropertyDescriptor,
        value: Meta,
    ): DeviceOutcome<Unit> = c.write(property, value)
    context(device: DeviceEnvironment)
    override suspend fun execute(
        action: ActionDescriptor,
        argument: Meta?,
    ): DeviceOutcome<Meta?> = c.execute(action, argument)
    override fun close() = c.close()
}

/** Picks the concrete class based on whether `onStep` was declared. */
internal fun inlineBackend(
    readers: Map<Name, DeviceReadBlock<Any>>,
    writers: Map<Name, DeviceWriteBlock>,
    valueWriters: Map<Name, (Any?) -> Unit>,
    actions: Map<Name, DeviceActionBlock>,
    stepBody: ((Duration) -> Unit)?,
    closeBody: (() -> Unit)?,
): DeviceBackend {
    val core = InlineBackendCore(readers, writers, valueWriters, actions, closeBody)
    return if (stepBody != null) InlineSteppedBackend(core, stepBody) else InlineDeviceBackend(core)
}

internal fun synthesizeProperty(name: Name, mutable: Boolean): PropertyDescriptor = PropertyDescriptor(
    name = name,
    kind = PropertyKind.LOGICAL,
    valueTypeId = TypeIds.META,
    metaDescriptor = MetaDescriptor(),
    attributes = setOf(AccessAttribute(readable = true, mutable = mutable)),
)

internal fun writeTypeError(property: String, expected: String, got: Meta): Nothing =
    throw DeviceFaultException(
        ValidationFault(
            details = Meta {
                "property" put property
                "expected" put expected
                "message" put "Property '$property' write requires a scalar $expected value."
                "actual" put got.toString()
            },
        ),
    )

private fun unknownInlineMember(code: String, message: String): Nothing =
    throw DeviceFaultException(GenericDeviceFault(code = code, message = message))

private fun inlineTypeError(property: Name, expected: String, got: Any): Nothing =
    throw DeviceFaultException(
        ValidationFault(
            details = Meta {
                "property" put property.toString()
                "expected" put expected
                "message" put "Property '$property' read requires a scalar $expected value."
                "actual" put (got::class.simpleName ?: got.toString())
            },
        ),
    )

private fun inlineWriteTypeError(property: Name, value: Any?, cause: Exception): Nothing =
    throw DeviceFaultException(
        ValidationFault(
            details = Meta {
                "property" put property.toString()
                "message" put "Property '$property' write received a value incompatible with its typed spec."
                "actual" put (value?.let { it::class.simpleName ?: it.toString() } ?: "null")
                "cause" put (cause.message ?: cause::class.simpleName.orEmpty())
            },
        ),
        cause,
    )

// ── DSL action return helpers (usable inside `device { action("x") { ... } }` blocks) ──

/** Shorthand for an action that returns no result. Equivalent to `null`. */
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
