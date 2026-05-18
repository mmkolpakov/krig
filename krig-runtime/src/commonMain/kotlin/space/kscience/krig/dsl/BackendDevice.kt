@file:OptIn(
    InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
)

package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.getOrThrow
import kotlinx.coroutines.CancellationException
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.ignoreNonCancellationFailure
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedAction
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/**
 * Descriptor source consulted by [BackendDevice] to resolve the full
 * property/action descriptor — **including attributes like `BindingsAttribute`
 * that protocol adapters rely on** — before delegating a call to the underlying
 * [DeviceBackend].
 */
public interface DescriptorSource {
    /** Returns the declared [PropertyDescriptor] for [name], or `null` if absent. */
    public fun property(name: Name): PropertyDescriptor?

    /** Returns the declared [ActionDescriptor] for [name], or `null` if absent. */
    public fun action(name: Name): ActionDescriptor?

    public companion object {
        /** The empty source. Only synthetic fallback descriptors are produced. */
        public val Empty: DescriptorSource = object : DescriptorSource {
            override fun property(name: Name): PropertyDescriptor? = null
            override fun action(name: Name): ActionDescriptor? = null
        }

        /** Builds a source from two maps (typical result of a Blueprint lookup). */
        public fun of(
            properties: Map<Name, PropertyDescriptor>,
            actions: Map<Name, ActionDescriptor> = emptyMap(),
        ): DescriptorSource = object : DescriptorSource {
            override fun property(name: Name): PropertyDescriptor? = properties[name]
            override fun action(name: Name): ActionDescriptor? = actions[name]
        }
    }
}

/**
 * Minimal [Device] delegating read / write / execute to a [DeviceBackend]. Use the
 * [device] factory; direct construction bypasses pipeline assembly.
 *
 * [descriptorSource] supplies declared descriptors so their attributes (e.g.
 * `BindingsAttribute`) reach protocol adapters. A missing source falls back to a
 * synthetic descriptor, which silently drops such attributes. That fallback is fine for
 * trivial simulations, but descriptor-aware adapters should use the [device] factory so
 * the original blueprint descriptors are preserved.
 */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
public class BackendDevice @InternalKrigApi constructor(
    private val backend: DeviceBackend,
    name: Name,
    runtime: DeviceRuntime,
    private val descriptorSource: DescriptorSource = DescriptorSource.Empty,
) : AbstractDevice(name, runtime) {
    @InternalKrigApi
    public constructor(
        backend: DeviceBackend,
        name: Name,
        context: Context,
        descriptorSource: DescriptorSource = DescriptorSource.Empty,
    ) : this(backend, name, DeviceRuntime(context), descriptorSource)

    private val typedBackend: TypedBackend? = backend as? TypedBackend
    private val typedDeviceBackend: TypedDeviceBackend? = backend as? TypedDeviceBackend

    override suspend fun readProperty(propertyName: Name): Meta =
        backend.read(descriptorSource.property(propertyName) ?: syntheticProperty(propertyName)).getOrThrow()

    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        writeToBackend(propertyName, value).getOrThrow()
        emitPropertyChanged(propertyName, value)
    }

    override suspend fun execute(actionName: Name, argument: Meta?): Meta? =
        backend.execute(descriptorSource.action(actionName) ?: syntheticAction(actionName), argument).getOrThrow()

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        typedBackend?.reader(spec) ?: GenericTypedReader { spec.converter.read(readProperty(spec.name)) }

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        typedBackend?.writer(spec) ?: GenericTypedWriter { value ->
            writeProperty(spec.name, spec.converter.convert(value))
        }

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        typedBackend?.sampler(spec)

    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        typedBackend?.action(spec) ?: GenericTypedAction { input ->
            val resultMeta = execute(spec.name, spec.inputConverter.convert(input))
            resultMeta?.let(spec.outputConverter::read)
        }

    /**
     * Delegates directly to [DeviceBackend.read] without the getOrThrow/re-wrap overhead.
     * The backend already returns [DeviceOutcome], so we pass it through unchanged.
     */
    override suspend fun readPropertyOutcome(propertyName: Name): DeviceOutcome<Meta> =
        backendOutcome { backend.read(descriptorSource.property(propertyName) ?: syntheticProperty(propertyName)) }

    /**
     * Delegates to [DeviceBackend.write] and emits [PropertyChangedMessage] on success.
     */
    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): DeviceOutcome<Unit> =
        when (val outcome = backendOutcome { writeToBackend(propertyName, value) }) {
            is DeviceOutcome.Ok -> {
                emitPropertyChanged(propertyName, value)
                outcome
            }
            is DeviceOutcome.Fail -> outcome
        }

    private suspend fun writeToBackend(propertyName: Name, value: Meta): DeviceOutcome<Unit> =
        backend.write(
            descriptorSource.property(propertyName) ?: syntheticProperty(propertyName),
            value,
        )

    private suspend fun emitPropertyChanged(propertyName: Name, value: Meta) {
        emit(
            PropertyChangedMessage(
                time = clock.now(),
                property = propertyName,
                value = value,
                sourceDevice = name,
            )
        )
    }

    /**
     * Delegates directly to [DeviceBackend.execute] without the getOrThrow/re-wrap overhead.
     */
    override suspend fun executeOutcome(actionName: Name, argument: Meta?): DeviceOutcome<Meta?> =
        backendOutcome { backend.execute(descriptorSource.action(actionName) ?: syntheticAction(actionName), argument) }

    @OptIn(InternalKrigApi::class)
    override fun close() {
        ignoreNonCancellationFailure { backend.close() }
        super.close()
    }

    @OptIn(InternalKrigApi::class)
    override suspend fun shutdown() {
        ignoreCleanupFailureSuspending { backend.shutdown() }
        super.shutdown()
    }

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? {
        typedDeviceBackend?.propertySpec(propertyName)?.let { return it }
        val descriptor = descriptorSource.property(propertyName) ?: return null
        return if (descriptor.isMutable) {
            BackendMutableMetaPropertySpec(descriptor)
        } else {
            BackendMetaPropertySpec(descriptor)
        }
    }

    override fun actionSpec(actionName: Name): DeviceActionContract<*, *>? =
        typedDeviceBackend?.actionSpec(actionName)
            ?: descriptorSource.action(actionName)?.let(::BackendMetaActionSpec)

    private fun syntheticProperty(name: Name): PropertyDescriptor = PropertyDescriptor(
        name = name,
        kind = PropertyKind.LOGICAL,
        valueTypeId = TypeIds.META,
        metaDescriptor = MetaDescriptor(),
    )

    private fun syntheticAction(name: Name): ActionDescriptor = ActionDescriptor(name = name)

    private suspend inline fun <T> backendOutcome(block: suspend () -> DeviceOutcome<T>): DeviceOutcome<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            updateLifecycleState(LifecycleState.Failed(e))
            throw e
        }
}

private val PropertyDescriptor.isMutable: Boolean
    get() = attributes.filterIsInstance<AccessAttribute>().any { it.mutable }

private open class BackendMetaPropertySpec(
    final override val descriptor: PropertyDescriptor,
) : DevicePropertySpec<BackendDevice, Meta> {
    final override val name: Name = descriptor.name
    final override val converter: MetaConverter<Meta> = MetaConverter.meta

    override suspend fun read(device: BackendDevice): Meta =
        device.readProperty(name)
}

private class BackendMutableMetaPropertySpec(
    descriptor: PropertyDescriptor,
) : BackendMetaPropertySpec(descriptor), MutableDevicePropertySpec<BackendDevice, Meta> {
    override suspend fun write(device: BackendDevice, value: Meta) {
        device.writeProperty(name, value)
    }
}

private class BackendMetaActionSpec(
    override val descriptor: ActionDescriptor,
) : DeviceActionSpec<BackendDevice, Meta, Meta> {
    override val name: Name = descriptor.name
    override val inputConverter: MetaConverter<Meta> = MetaConverter.meta
    override val outputConverter: MetaConverter<Meta> = MetaConverter.meta

    override suspend fun execute(device: BackendDevice, input: Meta): Meta? =
        device.execute(name, input)
}
