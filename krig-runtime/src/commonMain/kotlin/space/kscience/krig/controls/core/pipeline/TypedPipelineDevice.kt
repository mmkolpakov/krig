@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import space.kscience.attributes.Attributes
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.timeout
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.runCatchingDevice
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.DeviceCapability
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.OperationTracker
import space.kscience.krig.core.contracts.RuntimeCapabilityHost
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedAction
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed-primary pipeline decorator. Wraps a [delegate] [Device] so every
 * `reader(spec)` / `writer(spec)` / `execute(...)` call goes through the declarative
 * QoS pipeline ([ReadPipelineSpec], [WritePipelineSpec], [ActionPipelineSpec]).
 *
 * Cross-cutting concerns (gates, observers, timeout, retry, resource locks) are configured
 * via the spec data classes — adding a new concern is an additive `data class` field, not
 * a new chain-control interface.
 *
 * Constructor is `@InternalKrigApi`; assemble through [wrapWithTypedPipeline]
 * which wires per-device defaults (lifecycle, RBAC, audit, latency-budget).
 */
public class TypedPipelineDevice @InternalKrigApi constructor(
    @property:InternalKrigApi public val delegate: Device,
    private val readSpec: ReadPipelineSpec = ReadPipelineSpec.Empty,
    private val writeSpec: WritePipelineSpec = WritePipelineSpec.Empty,
    private val actionSpec: ActionPipelineSpec = ActionPipelineSpec.Empty,
    private val registry: ResourceLockRegistry = ResourceLockRegistry(),
    @property:InternalKrigApi public val capabilities: Attributes = Attributes.EMPTY,
) : Device by delegate, LifecycleStateHolder, RuntimeCapabilityHost {
    private val cacheLock = SynchronizedObject()
    private val readerCache = mutableMapOf<Name, Lazy<CachedReader>>()
    private val writerCache = mutableMapOf<Name, Lazy<CachedWriter>>()
    private val actionCache = mutableMapOf<Name, Lazy<CachedAction>>()

    override val lifecycleStateFlow: StateFlow<LifecycleState>?
        get() = (delegate as? LifecycleStateHolder)?.lifecycleStateFlow

    override fun updateLifecycleState(state: LifecycleState) {
        (delegate as? LifecycleStateHolder)?.updateLifecycleState(state)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DeviceCapability<*>> capability(key: CapabilityKey<C, *>): C? =
        installedCapabilities.firstOrNull { it.key == key || it.key.id == key.id } as? C
            ?: delegate.capability(key)

    @InternalKrigApi
    override val installedCapabilities: Collection<DeviceCapability<*>>
        get() = capabilities.content.values.filterIsInstance<DeviceCapability<*>>() +
                ((delegate as? RuntimeCapabilityHost)?.installedCapabilities.orEmpty())

    @InternalKrigApi
    override fun installCapability(capability: DeviceCapability<*>) {
        (delegate as? RuntimeCapabilityHost)?.installCapability(capability)
    }

    override fun close() {
        delegate.close()
    }

    override suspend fun shutdown() {
        delegate.shutdown()
    }

    // --- Typed contract: compile pipeline ONCE per reader/writer --------------------

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertySpec<*, T>): TypedReader<T> {
        val slot = synchronized(cacheLock) {
            readerCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedReader(
                        descriptor = spec.descriptor,
                        converter = spec.converter,
                        reader = compileReader(spec),
                    )
                }
            }
        }
        return slot.value.readerFor(spec)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T> {
        val slot = synchronized(cacheLock) {
            writerCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedWriter(
                        descriptor = spec.descriptor,
                        converter = spec.converter,
                        writer = compileWriter(spec),
                    )
                }
            }
        }
        return slot.value.writerFor(spec)
    }

    override fun <T> sampler(spec: DevicePropertySpec<*, T>): TypedSampler<T>? =
        delegate.sampler(spec)

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> action(spec: DeviceActionSpec<*, I, O>): TypedAction<I, O> {
        val executor = actionExecutor(spec as DeviceActionSpec<*, Any?, Any?>) as suspend (I) -> O?
        return GenericTypedAction { input -> executor(input) }
    }

    private fun <T> compileReader(spec: DevicePropertySpec<*, T>): TypedReader<T> {
        val raw = delegate.reader(spec)
        val decorated = readSpec.decorators.fold(raw) { acc, dec -> dec.decorate(spec, acc) }
        val delay = spec.descriptor.timeout ?: readSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: readSpec.defaultRetry

        val gates = readSpec.gates.map { gate -> suspend { gate.check(spec) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> readSpec.observers.forEach { try { it.onRead(spec, d, f) } catch (_: Throwable) {} } },
            terminal = { _: Unit -> decorated.read() },
        )
        val tracker = delegate as? OperationTracker
        return GenericTypedReader {
            if (tracker == null) {
                execute(Unit)
            } else {
                tracker.enterOperation()
                try {
                    execute(Unit)
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    private fun <T> compileWriter(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T> {
        val raw = delegate.writer(spec)
        val delay = spec.descriptor.timeout ?: writeSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: writeSpec.defaultRetry

        val gates = writeSpec.gates.map { gate -> suspend { gate.check(spec) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> writeSpec.observers.forEach { try { it.onWrite(spec, d, f) } catch (_: Throwable) {} } },
            terminal = { value: T -> raw.write(value) },
        )
        val tracker = delegate as? OperationTracker
        return GenericTypedWriter { value ->
            if (tracker == null) {
                execute(value)
            } else {
                tracker.enterOperation()
                try {
                    execute(value)
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    // --- Control-plane Meta boundary: route through typed pipeline when spec is known --
    //
    // When the delegate registers a [DevicePropertySpec] / [DeviceActionSpec] for the
    // requested name (typically through [DeviceBlueprint]), the serialization-facing
    // Meta API crosses into the typed executor so timeout / retry / locks / observers
    // all fire. Unknown names fail fast: the core runtime no longer fabricates synthetic
    // specs for unregistered Meta calls.

    @Suppress("UNCHECKED_CAST")
    override suspend fun readProperty(propertyName: Name): Meta {
        val spec = delegate.propertySpec(propertyName)
        if (spec != null) {
            val typedSpec = spec as DevicePropertySpec<*, Any?>
            val typed = reader(typedSpec).read()
            return encodeControlPlaneMeta(typedSpec.converter, typed, "property", propertyName)
        }
        unknownProperty(propertyName, "read")
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        val spec = delegate.propertySpec(propertyName) as? MutableDevicePropertySpec<*, *>
        if (spec != null) {
            val mutable = spec as MutableDevicePropertySpec<*, Any?>
            val decoded = decodeControlPlaneMeta(mutable.converter, value, "property", propertyName)
            writer(mutable).write(decoded)
            return
        }
        unknownProperty(propertyName, "write")
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? {
        val spec = delegate.actionSpec(actionName)
        if (spec != null) {
            val typedSpec = spec as DeviceActionSpec<*, Any?, Any?>
            val decoded = if (argument != null) {
                decodeControlPlaneMeta(typedSpec.inputConverter, argument, "action", actionName)
            } else null
            val result = actionExecutor(typedSpec)(decoded)
            return result?.let { encodeControlPlaneMeta(typedSpec.outputConverter, it, "action", actionName) }
        }
        unknownAction(actionName)
    }

    private fun actionExecutor(spec: DeviceActionSpec<*, Any?, Any?>): suspend (Any?) -> Any? {
        val slot = synchronized(cacheLock) {
            actionCache.getOrPut(spec.name) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    CachedAction(
                        descriptor = spec.descriptor,
                        inputConverter = spec.inputConverter,
                        outputConverter = spec.outputConverter,
                        executor = compileAction(spec),
                    )
                }
            }
        }
        // Compatibility is checked before the erased executor leaves the cache boundary.
        // If a caller reuses the same action name with another descriptor/converter set,
        // fail here rather than later inside the coroutine pipeline.
        return slot.value.executorFor(spec)
    }

    private fun <I, O> compileAction(spec: DeviceActionSpec<*, I, O>): suspend (I) -> O? {
        val delay = spec.descriptor.timeout ?: actionSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: actionSpec.defaultRetry

        val gates = actionSpec.gates.map { gate -> suspend { gate.check(spec) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> actionSpec.observers.forEach { try { it.onAction(spec, d, f) } catch (_: Throwable) {} } },
            terminal = { input: I ->
                val argMeta = if (input != null) spec.inputConverter.convert(input) else null
                val resultMeta = delegate.execute(spec.name, argMeta)
                if (resultMeta != null) spec.outputConverter.read(resultMeta) else null
            },
        )
        val tracker = delegate as? OperationTracker
        return { input ->
            if (tracker == null) {
                execute(input)
            } else {
                tracker.enterOperation()
                try {
                    execute(input)
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    // --- Outcome variants ------------------------------------------------------------

    override suspend fun readPropertyOutcome(propertyName: Name): DeviceOutcome<Meta> =
        pipelineOutcome { readProperty(propertyName) }

    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): DeviceOutcome<Unit> =
        pipelineOutcome { writeProperty(propertyName, value) }

    override suspend fun executeOutcome(actionName: Name, argument: Meta?): DeviceOutcome<Meta?> =
        pipelineOutcome { execute(actionName, argument) }

    private suspend inline fun <T> pipelineOutcome(block: suspend () -> T): DeviceOutcome<T> =
        try {
            runCatchingDevice { block() }
        } catch (e: RuntimeException) {
            updateLifecycleState(LifecycleState.Failed(e))
            throw e
        }
}

private data class CachedReader(
    val descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    val converter: MetaConverter<*>,
    val reader: TypedReader<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> readerFor(spec: DevicePropertySpec<*, T>): TypedReader<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return reader as TypedReader<T>
    }
}

private data class CachedWriter(
    val descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    val converter: MetaConverter<*>,
    val writer: TypedWriter<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> writerFor(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return writer as TypedWriter<T>
    }
}

private data class CachedAction(
    val descriptor: space.kscience.krig.api.descriptors.ActionDescriptor,
    val inputConverter: MetaConverter<*>,
    val outputConverter: MetaConverter<*>,
    val executor: suspend (Any?) -> Any?,
) {
    fun executorFor(spec: DeviceActionSpec<*, Any?, Any?>): suspend (Any?) -> Any? {
        check(descriptor == spec.descriptor && inputConverter === spec.inputConverter && outputConverter === spec.outputConverter) {
            "Action '${spec.name}' was requested with a different descriptor or converter instance."
        }
        return executor
    }
}

private fun CachedReader.requireCompatible(
    descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

private fun CachedWriter.requireCompatible(
    descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

private fun unknownProperty(propertyName: Name, operation: String): Nothing {
    throw DeviceFaultException(
        GenericDeviceFault(
            code = "UNKNOWN_PROPERTY",
            message = "Cannot $operation property '$propertyName': no DevicePropertySpec is registered.",
        ),
    )
}

private fun unknownAction(actionName: Name): Nothing {
    throw DeviceFaultException(
        GenericDeviceFault(
            code = "UNKNOWN_ACTION",
            message = "Cannot execute action '$actionName': no DeviceActionSpec is registered.",
        ),
    )
}

private fun <T> decodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: Meta,
    kind: String,
    name: Name,
): T {
    try {
        converter.readOrNull(value)?.let { return it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceFaultException) {
        throw e
    } catch (e: Exception) {
        invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
    }
    invalidControlPlanePayload(kind, name, "Payload does not match the registered converter.", null)
}

private fun <T> encodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: T,
    kind: String,
    name: Name,
): Meta {
    try {
        return converter.convert(value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceFaultException) {
        throw e
    } catch (e: Exception) {
        invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
    }
}

private fun invalidControlPlanePayload(
    kind: String,
    name: Name,
    message: String,
    cause: Throwable?,
): Nothing {
    throw DeviceFaultException(
        ValidationFault(
            details = Meta {
                "kind" put kind
                "name" put name.toString()
                "message" put message
                if (cause != null) {
                    "causeType" put (cause::class.simpleName ?: "Exception")
                    "causeMessage" put (cause.message ?: cause.toString())
                }
            },
        ),
        cause,
    )
}
