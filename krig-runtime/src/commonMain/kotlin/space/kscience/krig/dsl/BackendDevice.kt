@file:OptIn(
    InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
)

package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import kotlinx.coroutines.CancellationException
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.ignoreNonCancellationFailure
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedBackend
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.io.Binary
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
        /** Empty descriptor source. Undeclared Meta properties require explicit dynamic-property mode. */
        public val Empty: DescriptorSource = object : DescriptorSource {
            override fun property(name: Name): PropertyDescriptor? = null
            override fun action(name: Name): ActionDescriptor? = null
        }

        /** Builds a source from two maps (typical result of a Manifest lookup). */
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
 * [descriptorSource] supplies declared descriptors so their attributes reach protocol
 * adapters. Undeclared Meta calls are rejected unless [allowAdHocProperties] is enabled
 * explicitly for legacy adapters and notebooks.
 */
@OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)
public class BackendDevice @InternalKrigApi constructor(
    private val backend: DeviceBackend,
    name: Name,
    runtime: DeviceRuntime,
    private val descriptorSource: DescriptorSource = DescriptorSource.Empty,
    private val allowAdHocProperties: Boolean = false,
) : AbstractDevice(name, runtime) {
    @InternalKrigApi
    public constructor(
        backend: DeviceBackend,
        name: Name,
        context: Context,
        descriptorSource: DescriptorSource = DescriptorSource.Empty,
        allowAdHocProperties: Boolean = false,
    ) : this(backend, name, DeviceRuntime(context), descriptorSource, allowAdHocProperties)

    private val contractBackend: TypedBackend? = backend as? TypedBackend
    private val typedDeviceBackend: TypedDeviceBackend? = backend as? TypedDeviceBackend

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        contractBackend?.reader(spec) ?: TypedReader { spec.converter.read(readProperty(spec.name)) }

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        contractBackend?.writer(spec) ?: TypedWriter { value ->
            writeProperty(spec.name, spec.converter.convert(value))
        }

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        contractBackend?.sampler(spec)

    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        contractBackend?.action(spec) ?: TypedAction { input ->
            val resultMeta = execute(spec.name, spec.inputConverter.convert(input))
            resultMeta?.let(spec.outputConverter::read)
        }

    /**
     * Delegates directly to [DeviceBackend.read] without the getOrThrow/re-wrap overhead.
     * The backend already returns [OperationOutcome], so we pass it through unchanged.
     */
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        when (val descriptor = propertyDescriptor(propertyName, "read")) {
            is OperationOutcome.Fail -> descriptor
            is OperationOutcome.Ok -> backendOutcome { backend.read(descriptor.value) }
        }

    /**
     * Delegates to [DeviceBackend.write] and emits [PropertyChangedMessage] on success.
     */
    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        when (val outcome = backendOutcome { writeToBackend(propertyName, value) }) {
            is OperationOutcome.Ok -> {
                emitPropertyChanged(propertyName, value)
                outcome
            }
            is OperationOutcome.Fail -> outcome
        }

    private suspend fun writeToBackend(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        when (val descriptor = propertyDescriptor(propertyName, "write")) {
            is OperationOutcome.Fail -> descriptor
            is OperationOutcome.Ok -> backend.write(descriptor.value, value)
        }

    /**
     * Emits every successful write. Thinning and compression are storage/acquisition
     * policies, not hidden transport gates in the device primitive.
     */
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
    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        when (val descriptor = actionDescriptor(actionName)) {
            is OperationOutcome.Fail -> descriptor
            is OperationOutcome.Ok -> backendOutcome { backend.execute(descriptor.value, argument) }
        }

    override suspend fun doReadObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        when (val descriptor = propertyDescriptor(propertyName, "read")) {
            is OperationOutcome.Fail -> descriptor
            is OperationOutcome.Ok -> backendOutcome { backend.readObserved(descriptor.value) }
        }

    override suspend fun doReadBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        val descriptors = LinkedHashMap<Name, PropertyDescriptor>()
        val results = LinkedHashMap<Name, OperationOutcome<ObservedValue<Meta?>>>()
        for (propertyName in properties) {
            when (val descriptor = propertyDescriptor(propertyName, "read")) {
                is OperationOutcome.Ok -> descriptors[propertyName] = descriptor.value
                is OperationOutcome.Fail -> results[propertyName] = descriptor
            }
        }
        if (descriptors.isEmpty()) return results

        val batchOutcome = backendOutcome {
            OperationOutcome.Ok(backend.readBatchObserved(descriptors.values))
        }
        when (batchOutcome) {
            is OperationOutcome.Fail -> {
                descriptors.keys.forEach { propertyName -> results[propertyName] = batchOutcome }
            }
            is OperationOutcome.Ok -> {
                for ((propertyName, descriptor) in descriptors) {
                    results[propertyName] = batchOutcome.value[descriptor.name]
                        ?: OperationOutcome.Fail(
                            GenericOperationFault(
                                message = "Backend batch read did not return property '$propertyName'.",
                            ),
                        )
                }
            }
        }
        return results
    }

    override suspend fun doReadBinaryOutcome(propertyName: Name): OperationOutcome<Binary> =
        when (val descriptor = propertyDescriptor(propertyName, "read")) {
            is OperationOutcome.Fail -> descriptor
            is OperationOutcome.Ok -> backendOutcome { backend.readBinary(descriptor.value) }
        }

    override suspend fun doReadBatchBinaryOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<Binary>> {
        val descriptors = LinkedHashMap<Name, PropertyDescriptor>()
        val results = LinkedHashMap<Name, OperationOutcome<Binary>>()
        for (propertyName in properties) {
            when (val descriptor = propertyDescriptor(propertyName, "read")) {
                is OperationOutcome.Ok -> descriptors[propertyName] = descriptor.value
                is OperationOutcome.Fail -> results[propertyName] = descriptor
            }
        }
        if (descriptors.isEmpty()) return results

        val batchOutcome = backendOutcome {
            OperationOutcome.Ok(backend.readBatchBinary(descriptors.values))
        }
        when (batchOutcome) {
            is OperationOutcome.Fail -> {
                descriptors.keys.forEach { propertyName -> results[propertyName] = batchOutcome }
            }
            is OperationOutcome.Ok -> {
                for ((propertyName, descriptor) in descriptors) {
                    results[propertyName] = batchOutcome.value[descriptor.name]
                        ?: OperationOutcome.Fail(
                            GenericOperationFault(
                                message = "Backend batch binary read did not return property '$propertyName'.",
                            ),
                        )
                }
            }
        }
        return results
    }

    override suspend fun doWriteBatchOutcome(
        values: Map<Name, Meta>,
    ): Map<Name, OperationOutcome<Unit>> {
        val descriptors = LinkedHashMap<Name, PropertyDescriptor>()
        val backendValues = LinkedHashMap<PropertyDescriptor, Meta>()
        val results = LinkedHashMap<Name, OperationOutcome<Unit>>()
        for ((propertyName, value) in values) {
            when (val descriptor = propertyDescriptor(propertyName, "write")) {
                is OperationOutcome.Ok -> {
                    descriptors[propertyName] = descriptor.value
                    backendValues[descriptor.value] = value
                }
                is OperationOutcome.Fail -> results[propertyName] = descriptor
            }
        }
        if (backendValues.isEmpty()) return results

        val batchOutcome = backendOutcome {
            OperationOutcome.Ok(backend.writeBatch(backendValues))
        }
        when (batchOutcome) {
            is OperationOutcome.Fail -> {
                descriptors.keys.forEach { propertyName -> results[propertyName] = batchOutcome }
            }
            is OperationOutcome.Ok -> {
                for ((propertyName, descriptor) in descriptors) {
                    val outcome = batchOutcome.value[descriptor.name]
                        ?: OperationOutcome.Fail(
                            GenericOperationFault(
                                message = "Backend batch write did not return property '$propertyName'.",
                            ),
                        )
                    results[propertyName] = outcome
                    if (outcome is OperationOutcome.Ok) {
                        emitPropertyChanged(propertyName, values.getValue(propertyName))
                    }
                }
            }
        }
        return results
    }

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
        val declared = descriptorSource.property(propertyName)
        val descriptor = declared ?: if (allowAdHocProperties) syntheticProperty(propertyName) else return null
        return if (descriptor.isMutable || (declared == null && allowAdHocProperties)) {
            BackendMutableMetaPropertyContract(descriptor)
        } else {
            BackendMetaPropertyContract(descriptor)
        }
    }

    override fun actionSpec(actionName: Name): DeviceActionContract<*, *>? =
        typedDeviceBackend?.actionSpec(actionName)
            ?: descriptorSource.action(actionName)?.let(::BackendMetaActionContract)

    private fun syntheticProperty(name: Name): PropertyDescriptor = PropertyDescriptor(
        name = name,
        kind = PropertyKind.LOGICAL,
        valueTypeId = TypeIds.META,
        metaDescriptor = MetaDescriptor(),
    )

    private fun propertyDescriptor(propertyName: Name, operation: String): OperationOutcome<PropertyDescriptor> {
        typedDeviceBackend?.propertySpec(propertyName)?.descriptor?.let { return OperationOutcome.Ok(it) }
        descriptorSource.property(propertyName)?.let { return OperationOutcome.Ok(it) }
        return if (allowAdHocProperties) {
            OperationOutcome.Ok(syntheticProperty(propertyName))
        } else {
            OperationOutcome.Fail(
                GenericOperationFault(
                    faultType = OperationFaultTypes.UnknownProperty,
                    message = "Cannot $operation undeclared property '$propertyName'.",
                    details = Meta {
                        OperationFaultDetails.PROPERTY put propertyName.toString()
                        OperationFaultDetails.OPERATION put operation
                    },
                ),
            )
        }
    }

    private fun actionDescriptor(actionName: Name): OperationOutcome<ActionDescriptor> {
        typedDeviceBackend?.actionSpec(actionName)?.descriptor?.let { return OperationOutcome.Ok(it) }
        descriptorSource.action(actionName)?.let { return OperationOutcome.Ok(it) }
        return OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownAction,
                message = "Cannot execute undeclared action '$actionName'.",
                details = Meta { OperationFaultDetails.ACTION put actionName.toString() },
            ),
        )
    }

    private suspend inline fun <T> backendOutcome(block: suspend () -> OperationOutcome<T>): OperationOutcome<T> =
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
    get() = attributes[OperationAttributeKeys.Access]?.mutable == true

private open class BackendMetaPropertyContract(
    final override val descriptor: PropertyDescriptor,
) : DevicePropertyContract<Meta> {
    final override val name: Name = descriptor.name
    final override val converter: MetaConverter<Meta> = MetaConverter.meta
}

private class BackendMutableMetaPropertyContract(
    descriptor: PropertyDescriptor,
) : BackendMetaPropertyContract(descriptor), MutableDevicePropertyContract<Meta>

private class BackendMetaActionContract(
    override val descriptor: ActionDescriptor,
) : DeviceActionContract<Meta, Meta> {
    override val name: Name = descriptor.name
    override val inputConverter: MetaConverter<Meta> = MetaConverter.meta
    override val outputConverter: MetaConverter<Meta> = MetaConverter.meta
}
