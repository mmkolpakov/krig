package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.features.PipelineFeature
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.TransportBackend
import space.kscience.krig.core.contracts.booleanValue
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.intValue
import space.kscience.krig.core.contracts.longValue
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.wrapWithPipeline
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.DFBuilder
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Duration

/**
 * Shared surface for `device { … }` in both forms. Concrete builders are
 * [DeclarativeDeviceBuilder] (declarative properties / actions) and [ExplicitDeviceBuilder]
 * (pre-built backend). State lives in [DeviceBuilderCore]; concrete builders compose it
 * via `by core`.
 */
@DFBuilder
@KrigDsl
public sealed interface DeviceBuilder {
    public val deviceName: Name
    public val deviceContext: Context

    /**
     * Allows string/Meta property calls not declared by a manifest or DSL property.
     *
     * When this flag is `true`, unknown calls are served through synthetic descriptors.
     * Keep it `false` for contract-checked devices; enable it only for legacy adapters,
     * probes, and notebook experiments where the schema is intentionally open.
     */
    public var allowAdHocProperties: Boolean

    /** Installs descriptors from a [DeviceManifest]. */
    public fun manifest(manifest: DeviceManifest)

    /**
     * Installs a [PipelineFeature] and configures its runtime block.
     *
     * Feature objects are supplied by integrations and demos. Common IO policies such as
     * timeouts, retries, and locks usually live on operation descriptors and are applied
     * by the pipeline around backend calls.
    */
    public fun <C : Any> install(
        pipelineFeature: PipelineFeature<C, *>,
        configure: C.() -> Unit = {},
    )
}

internal interface DescriptorSourceOwner {
    fun descriptors(source: DescriptorSource)
}

/** Read scope for device DSL blocks. Exposes safe self-device reads without leaking lifecycle controls. */
public interface DeviceReadScope : DeviceEnvironment {
    public suspend fun readProperty(name: Name): Meta
    public suspend fun readProperty(name: String): Meta = readProperty(name.asName())

    public suspend fun <T> read(spec: DevicePropertyContract<T>): T
    public suspend fun readDouble(name: Name): Double
    public suspend fun readDouble(name: String): Double = readDouble(name.asName())
    public suspend fun readInt(name: Name): Int
    public suspend fun readInt(name: String): Int = readInt(name.asName())
    public suspend fun readLong(name: Name): Long
    public suspend fun readLong(name: String): Long = readLong(name.asName())
    public suspend fun readBoolean(name: Name): Boolean
    public suspend fun readBoolean(name: String): Boolean = readBoolean(name.asName())
    public suspend fun readString(name: Name): String
    public suspend fun readString(name: String): String = readString(name.asName())
}

/** Write scope for device DSL blocks. */
public interface DeviceWriteScope : DeviceReadScope {
    public suspend fun writeProperty(name: Name, value: Meta)
    public suspend fun writeProperty(name: String, value: Meta): Unit = writeProperty(name.asName(), value)

    public suspend fun <T> write(spec: MutableDevicePropertyContract<T>, value: T)
    public suspend fun writeDouble(name: Name, value: Double)
    public suspend fun writeDouble(name: String, value: Double): Unit = writeDouble(name.asName(), value)
    public suspend fun writeInt(name: Name, value: Int)
    public suspend fun writeInt(name: String, value: Int): Unit = writeInt(name.asName(), value)
    public suspend fun writeLong(name: Name, value: Long)
    public suspend fun writeLong(name: String, value: Long): Unit = writeLong(name.asName(), value)
    public suspend fun writeBoolean(name: Name, value: Boolean)
    public suspend fun writeBoolean(name: String, value: Boolean): Unit = writeBoolean(name.asName(), value)
    public suspend fun writeString(name: Name, value: String)
    public suspend fun writeString(name: String, value: String): Unit = writeString(name.asName(), value)
}

/** Action scope for device DSL blocks. */
public interface DeviceActionScope : DeviceWriteScope {
    public suspend fun execute(action: Name, argument: Meta? = null): Meta?
    public suspend fun execute(action: String, argument: Meta? = null): Meta? = execute(action.asName(), argument)

    public suspend fun <I, O> execute(spec: DeviceActionContract<I, O>, input: I): O?
}

/** Read block with a narrow self-device receiver. */
public fun interface DeviceReadBlock<out T> {
    public suspend fun DeviceReadScope.read(): T
}

/** Write block with a narrow self-device receiver. */
public fun interface DeviceWriteBlock {
    public suspend fun DeviceWriteScope.write(value: Meta)
}

/** Action block with a narrow self-device receiver. */
public fun interface DeviceActionBlock {
    public suspend fun DeviceActionScope.execute(argument: Meta?): Meta?
}

/** Shared mutable state for [DeviceBuilder]; composed via delegation, not inherited. */
@InternalKrigApi
internal class DeviceBuilderCore internal constructor(
    override val deviceName: Name,
    val runtime: DeviceRuntime,
) : DeviceBuilder, DescriptorSourceOwner {
    override val deviceContext: Context get() = runtime.context

    @PublishedApi internal val pipeline: PipelineBuilder = PipelineBuilder()

    override var allowAdHocProperties: Boolean = false

    @PublishedApi
    internal var descriptorSource: DescriptorSource = DescriptorSource.Empty
        private set

    override fun descriptors(source: DescriptorSource) {
        descriptorSource = source
    }

    override fun manifest(manifest: DeviceManifest) {
        descriptorSource = DescriptorSource.of(manifest.properties, manifest.actions)
    }

    override fun <C : Any> install(
        pipelineFeature: PipelineFeature<C, *>,
        configure: C.() -> Unit,
    ) {
        val config = pipelineFeature.createConfig().apply(configure)
        pipelineFeature.install(config, pipeline)
    }

    @PublishedApi
    internal suspend fun assemble(backend: DeviceBackend): Device {
        if (backend is TransportBackend) {
            pipeline.useConnectionState { backend.connectionState.value }
        }
        val baseDevice = BackendDevice(
            backend = backend,
            name = deviceName,
            runtime = runtime,
            descriptorSource = descriptorSource,
            allowAdHocProperties = allowAdHocProperties,
        )
        return wrapWithPipeline(baseDevice, pipeline, deviceName.toString())
    }
}

/**
 * Declarative builder. `property` / `mutableProperty` / `action` / `onStep` / `onClose`
 * describe the device in-place; the builder synthesises a [DeviceBackend] automatically.
 *
 * Reader / writer / action lambdas receive narrow self-device scopes. The scopes expose
 * `clock`, `deviceScope`, `name`, and same-device `readProperty`/`writeProperty`/`execute`
 * helpers without leaking lifecycle controls from [Device].
 *
 * ```kotlin
 * device("thermo") {
 *     property("uptime") { clock.now().toEpochMilliseconds() }
 * }
 * ```
 */
@DFBuilder
@KrigDsl
@OptIn(InternalKrigApi::class)
public class DeclarativeDeviceBuilder @PublishedApi internal constructor(
    @PublishedApi internal val core: DeviceBuilderCore,
) : DeviceBuilder by core {

    @PublishedApi
    internal constructor(name: Name, context: Context) : this(DeviceBuilderCore(name, DeviceRuntime(context)))

    @PublishedApi
    internal constructor(name: Name, runtime: DeviceRuntime) : this(DeviceBuilderCore(name, runtime))

    @PublishedApi internal val readers: MutableMap<Name, DeviceReadBlock<Any>> = mutableMapOf()
    @PublishedApi internal val writers: MutableMap<Name, DeviceWriteBlock> = mutableMapOf()
    @PublishedApi internal val valueWriters: MutableMap<Name, (Any?) -> Unit> = mutableMapOf()
    @PublishedApi internal val actions: MutableMap<Name, DeviceActionBlock> = mutableMapOf()
    @PublishedApi internal val propertyDescriptors: MutableMap<Name, PropertyDescriptor> = mutableMapOf()
    @PublishedApi internal val actionDescriptors: MutableMap<Name, ActionDescriptor> = mutableMapOf()
    @PublishedApi internal var stepBody: ((Duration) -> Unit)? = null
    @PublishedApi internal var closeBody: (() -> Unit)? = null

    /** Read-only property whose value is computed on each read. */
    public fun property(name: String, read: DeviceReadBlock<Any>): Unit = property(name.asName(), read)

    /** Read-only property keyed by [Name]. */
    public fun property(name: Name, read: DeviceReadBlock<Any>) {
        readers[name] = read
        propertyDescriptors[name] = synthesizeProperty(name, mutable = false)
    }

    /** Typed read-only [Double] property. */
    public fun propertyDouble(name: String, read: DeviceReadBlock<Double>): Unit = property(name, read)

    /** Typed read-only [Int] property. */
    public fun propertyInt(name: String, read: DeviceReadBlock<Int>): Unit = property(name, read)

    /** Typed read-only [Boolean] property. */
    public fun propertyBoolean(name: String, read: DeviceReadBlock<Boolean>): Unit = property(name, read)

    /** Typed read-only [String] property. */
    public fun propertyString(name: String, read: DeviceReadBlock<String>): Unit = property(name, read)

    /** Mutable [Double] property backed by an internal atomic cell. */
    public fun mutableProperty(name: String, initial: Double) {
        declareCell(name, initial) { it.doubleValue ?: writeTypeError(name, "Double", it) }
    }

    /** Mutable [Int] property. */
    public fun mutableProperty(name: String, initial: Int) {
        declareCell(name, initial) { it.intValue ?: writeTypeError(name, "Int", it) }
    }

    /** Mutable [Long] property. */
    public fun mutableProperty(name: String, initial: Long) {
        declareCell(name, initial) { it.longValue ?: writeTypeError(name, "Long", it) }
    }

    /** Mutable [Boolean] property. */
    public fun mutableProperty(name: String, initial: Boolean) {
        declareCell(name, initial) { it.booleanValue ?: writeTypeError(name, "Boolean", it) }
    }

    /** Mutable [String] property. */
    public fun mutableProperty(name: String, initial: String) {
        declareCell(name, initial) { it.stringValue ?: writeTypeError(name, "String", it) }
    }

    /** Action that takes an optional [Meta] argument and returns an optional [Meta] result. */
    public fun action(name: String, body: DeviceActionBlock): Unit =
        action(name.asName(), body)

    /** Action keyed by [Name]. */
    public fun action(name: Name, body: DeviceActionBlock) {
        actions[name] = body
        actionDescriptors[name] = ActionDescriptor(name = name)
    }

    /** Step body invoked by the simulation coordinator with the current `dt`. */
    public fun onStep(block: (dt: Duration) -> Unit) {
        check(stepBody == null) { "onStep was already declared on this builder" }
        stepBody = block
    }

    /** Optional `close()` hook invoked when the backend is shut down. */
    public fun onClose(block: () -> Unit) {
        check(closeBody == null) { "onClose was already declared on this builder" }
        closeBody = block
    }

    // ── Property delegates — `val temperature by readDouble { … }` etc. ──
    // DSL blocks receive narrow self-device scopes so computed properties can read siblings.

    /** Delegate: `val temperature by readDouble { readSensor() }`. */
    public fun readDouble(
        read: DeviceReadBlock<Double>,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            propertyDouble(prop.name, read)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val count by readInt { counter }`. */
    public fun readInt(
        read: DeviceReadBlock<Int>,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            propertyInt(prop.name, read)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val enabled by readBoolean { isOn }`. */
    public fun readBoolean(
        read: DeviceReadBlock<Boolean>,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            propertyBoolean(prop.name, read)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val label by readString { name }`. */
    public fun readString(
        read: DeviceReadBlock<String>,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            propertyString(prop.name, read)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val setpoint by mutable(20.0)`. */
    public fun mutable(initial: Double): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            mutableProperty(prop.name, initial)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val count by mutable(0)`. */
    public fun mutable(initial: Int): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            mutableProperty(prop.name, initial)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val enabled by mutable(false)`. */
    public fun mutable(initial: Boolean): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            mutableProperty(prop.name, initial)
            ReadOnlyProperty { _, _ -> }
        }

    /** Delegate: `val label by mutable("default")`. */
    public fun mutable(initial: String): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Unit>> =
        PropertyDelegateProvider { _, prop ->
            mutableProperty(prop.name, initial)
            ReadOnlyProperty { _, _ -> }
        }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun <T : Any> declareCell(name: String, initial: T, decode: (Meta) -> T) {
        val key = name.asName()
        // Atomic ensures visibility across coroutines on different dispatchers.
        val cell = kotlin.concurrent.atomics.AtomicReference(initial)
        // Cell-backed mutables don't use the DeviceEnvironment context.
        readers[key] = DeviceReadBlock { cell.load() }
        writers[key] = DeviceWriteBlock { meta -> cell.store(decode(meta)) }
        valueWriters[key] = { value ->
            val checked = checkCellValue(name, initial, value)
            @Suppress("UNCHECKED_CAST")
            cell.store(checked as T)
        }
        propertyDescriptors[key] = synthesizeProperty(key, mutable = true)
    }

    internal suspend fun build(): Device {
        check(readers.isNotEmpty() || actions.isNotEmpty() || stepBody != null) {
            "Device DSL '$deviceName' has no property / action / onStep — declare at least one, " +
                    "or pass an explicit backend via device(name, backend)."
        }
        val backend: DeviceBackend = declarativeBackend(
            readers = readers.toMap(),
            writers = writers.toMap(),
            valueWriters = valueWriters.toMap(),
            actions = actions.toMap(),
            stepBody = stepBody,
            closeBody = closeBody,
        )
        // Promote synthesised descriptors unless the user supplied an explicit source.
        if (core.descriptorSource === DescriptorSource.Empty) {
            core.descriptors(DescriptorSource.of(propertyDescriptors.toMap(), actionDescriptors.toMap()))
        }
        return core.assemble(backend)
    }
}

private fun checkCellValue(name: String, initial: Any, value: Any?): Any =
    when (initial) {
        is Double -> value as? Double ?: cellTypeMismatch(name, "Double", value)
        is Int -> value as? Int ?: cellTypeMismatch(name, "Int", value)
        is Long -> value as? Long ?: cellTypeMismatch(name, "Long", value)
        is Boolean -> value as? Boolean ?: cellTypeMismatch(name, "Boolean", value)
        is String -> value as? String ?: cellTypeMismatch(name, "String", value)
        else -> value ?: cellTypeMismatch(name, initial::class.simpleName ?: "non-null value", null)
    }

private fun cellTypeMismatch(name: String, expected: String, value: Any?): Nothing {
    val actual = value?.let { it::class.simpleName ?: it.toString() } ?: "null"
    throw IllegalArgumentException("Property '$name' expected $expected but got $actual.")
}

/**
 * Builder used when a pre-constructed [DeviceBackend] is supplied. Only features and
 * descriptors can be added — declarative `property` / `action` helpers are unavailable
 * by construction, not by runtime check.
 */
@DFBuilder
@KrigDsl
@OptIn(InternalKrigApi::class)
public class ExplicitDeviceBuilder @PublishedApi internal constructor(
    @PublishedApi internal val core: DeviceBuilderCore,
    private val backend: DeviceBackend,
) : DeviceBuilder by core {

    @PublishedApi
    internal constructor(name: Name, context: Context, backend: DeviceBackend) :
        this(DeviceBuilderCore(name, DeviceRuntime(context)), backend)

    @PublishedApi
    internal constructor(name: Name, runtime: DeviceRuntime, backend: DeviceBackend) :
        this(DeviceBuilderCore(name, runtime), backend)

    internal suspend fun build(): Device = core.assemble(backend)
}
