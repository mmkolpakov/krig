@file:Suppress("RemoveRedundantQualifierName")
@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import space.kscience.attributes.Attributes
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.timeout
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.OperationTracker
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.contracts.CapabilityRegistry
import space.kscience.krig.core.contracts.CapabilityToggles
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedAction
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed-primary pipeline decorator. Wraps a [delegate] [Device] so every
 * `reader(spec)` / `writer(spec)` / `execute(...)` call goes through the declarative
 * operation QoS pipeline.
 *
 * Cross-cutting concerns (gates, observers, timeout, retry, resource locks) are configured
 * via the spec data classes — adding a new concern is an additive `data class` field, not
 * a new chain-control interface.
 *
 * Constructor is `@InternalKrigApi`; assemble through [wrapWithPipeline]
 * which wires per-device defaults (lifecycle, RBAC, audit, latency-budget).
 */
public class PipelineDevice @InternalKrigApi constructor(
    @property:InternalKrigApi public val delegate: Device,
    private val operationSpecs: Map<OperationKind, OperationPipelineSpec> = emptyMap(),
    private val readDecorators: List<ReadDecorator> = emptyList(),
    private val registry: ResourceLockRegistry = ResourceLockRegistry(),
    @property:InternalKrigApi public val capabilities: Attributes = Attributes.EMPTY,
) : Device by delegate, LifecycleStateHolder, CapabilityHost {
    private val cacheLock = SynchronizedObject()
    private val readerCache = mutableMapOf<Name, Lazy<CachedReader>>()
    private val writerCache = mutableMapOf<Name, Lazy<CachedWriter>>()
    private val actionCache = mutableMapOf<Name, Lazy<CachedAction>>()
    private val capabilityRegistry = CapabilityRegistry()
    private val detachLock = SynchronizedObject()
    private var detached = false

    override val capabilityToggles: CapabilityToggles =
        (delegate as? CapabilityHost)?.capabilityToggles ?: CapabilityToggles()

    override val lifecycleStateFlow: StateFlow<LifecycleState>?
        get() = (delegate as? LifecycleStateHolder)?.lifecycleStateFlow

    override fun updateLifecycleState(state: LifecycleState) {
        (delegate as? LifecycleStateHolder)?.updateLifecycleState(state)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        directCapabilities().firstOrNull { it.key == key || it.key.id == key.id } as? C
            ?: capabilityRegistry.capability(key)
            ?: delegate.capability(key)

    override val installedCapabilities: Collection<Capability<*>>
        get() = (
                directCapabilities() +
                        capabilityRegistry.installedCapabilities +
                        (delegate as? CapabilityHost)?.installedCapabilities.orEmpty()
                ).distinctBy { it.key.id }

    override fun registerCapability(capability: Capability<*>) {
        capabilityRegistry.registerCapability(capability)
    }

    override fun close() {
        deviceScope.launch(start = CoroutineStart.UNDISPATCHED) { detachCapabilitiesOnce() }
        delegate.close()
    }

    override suspend fun shutdown() {
        detachCapabilitiesOnce()
        delegate.shutdown()
    }

    private fun claimDetach(): Boolean = synchronized(detachLock) {
        if (detached) {
            false
        } else {
            detached = true
            true
        }
    }

    private suspend fun detachCapabilitiesOnce() {
        if (!claimDetach()) return
        val ownedCapabilities = (directCapabilities() + capabilityRegistry.installedCapabilities)
            .distinctBy { it.key.id }
            .asReversed()
        for (capability in ownedCapabilities) {
            ignoreCleanupFailureSuspending {
                context(this@PipelineDevice as CapabilityHost) { capability.onDetach() }
            }
        }
    }

    private fun directCapabilities(): Collection<Capability<*>> =
        capabilities.content.values.filterIsInstance<Capability<*>>()

    private fun operationSpec(kind: OperationKind): OperationPipelineSpec =
        operationSpecs[kind] ?: OperationPipelineSpec.Empty

    // --- Typed contract: compile pipeline ONCE per reader/writer --------------------

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> {
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
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
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

    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        delegate.sampler(spec)

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> {
        val executor = actionExecutor(spec as DeviceActionContract<Any?, Any?>) as suspend (I) -> O?
        return GenericTypedAction { input -> executor(input) }
    }

    private fun <T> compileReader(spec: DevicePropertyContract<T>): TypedReader<T> {
        val raw = delegate.reader(spec)
        val opSpec = operationSpec(OperationKinds.Read)
        val context = OperationContext(OperationKinds.Read, spec.name, spec.descriptor, name)
        val decorated = readDecorators.fold(raw) { acc, dec -> dec.decorate(spec, acc) }
        val delay = spec.descriptor.timeout ?: opSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: opSpec.defaultRetry

        val gates = opSpec.gates.map { gate -> suspend { gate.check(context) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> opSpec.observers.forEach { try { it.observe(context, d, f) } catch (_: Throwable) {} } },
            terminal = { _: Unit -> catchingOperationOutcome { decorated.read() } },
        )
        val tracker = delegate as? OperationTracker
        return GenericTypedReader {
            if (tracker == null) {
                execute(Unit).getOrThrow()
            } else {
                tracker.enterOperation()
                try {
                    execute(Unit).getOrThrow()
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    private fun <T> compileWriter(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        val raw = delegate.writer(spec)
        val opSpec = operationSpec(OperationKinds.Write)
        val context = OperationContext(OperationKinds.Write, spec.name, spec.descriptor, name)
        val delay = spec.descriptor.timeout ?: opSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: opSpec.defaultRetry

        val gates = opSpec.gates.map { gate -> suspend { gate.check(context) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> opSpec.observers.forEach { try { it.observe(context, d, f) } catch (_: Throwable) {} } },
            terminal = { value: T -> catchingOperationOutcome { raw.write(value) } },
        )
        val tracker = delegate as? OperationTracker
        return GenericTypedWriter { value ->
            if (tracker == null) {
                execute(value).getOrThrow()
            } else {
                tracker.enterOperation()
                try {
                    execute(value).getOrThrow()
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    // --- Control-plane Meta boundary: route through the operation pipeline when spec is known --
    //
    // When the delegate registers a [DevicePropertyContract] / [DeviceActionContract] for the
    // requested name (typically through [DeviceBlueprint]), the serialization-facing
    // Meta API crosses into the typed executor so timeout / retry / locks / observers
    // all fire. Unknown names fail fast: the core runtime no longer fabricates synthetic
    // specs for unregistered Meta calls.

    @Suppress("UNCHECKED_CAST")
    override suspend fun readProperty(propertyName: Name): Meta =
        readPropertyOutcome(propertyName).getOrThrow()

    @Suppress("UNCHECKED_CAST")
    override suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> {
        try {
            val spec = delegate.propertySpec(propertyName)
            if (spec != null) {
                val typedSpec = spec as DevicePropertyContract<Any?>
                return when (val typed = catchingOperationOutcome { reader(typedSpec).read() }) {
                    is OperationOutcome.Fail -> typed
                    is OperationOutcome.Ok -> encodeControlPlaneMeta(typedSpec.converter, typed.value, "property", propertyName)
                }
            }
            return unknownProperty(propertyName, "read")
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        writePropertyOutcome(propertyName, value).getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> {
        try {
            val spec = delegate.propertySpec(propertyName) as? MutableDevicePropertyContract<*>
            if (spec != null) {
                val mutable = spec as MutableDevicePropertyContract<Any?>
                return when (val decoded = decodeControlPlaneMeta(mutable.converter, value, "property", propertyName)) {
                    is OperationOutcome.Fail -> decoded
                    is OperationOutcome.Ok -> catchingOperationOutcome { writer(mutable).write(decoded.value) }
                }
            }
            return unknownProperty(propertyName, "write")
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? =
        executeOutcome(actionName, argument).getOrThrow()

    @Suppress("UNCHECKED_CAST")
    override suspend fun executeOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> {
        try {
            val spec = delegate.actionSpec(actionName)
                ?: return unknownAction(actionName)
            return executeControlPlaneAction(spec as DeviceActionContract<Any?, Any?>, actionName, argument)
        } catch (e: OperationFaultException) {
            return OperationOutcome.Fail(e.fault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            markFailure(e)
        }
    }

    private suspend fun executeControlPlaneAction(
        spec: DeviceActionContract<Any?, Any?>,
        actionName: Name,
        argument: Meta?,
    ): OperationOutcome<Meta?> {
        val decoded = if (argument != null) {
            decodeControlPlaneMeta(spec.inputConverter, argument, "action", actionName)
        } else {
            OperationOutcome.Ok(null)
        }
        if (decoded is OperationOutcome.Fail) return decoded

        val input = (decoded as OperationOutcome.Ok).value
        return when (val result = catchingOperationOutcome { actionExecutor(spec)(input) }) {
            is OperationOutcome.Fail -> result
            is OperationOutcome.Ok -> encodeActionResult(spec, actionName, result.value)
        }
    }

    private fun encodeActionResult(
        spec: DeviceActionContract<Any?, Any?>,
        actionName: Name,
        value: Any?,
    ): OperationOutcome<Meta?> =
        if (value == null) {
            OperationOutcome.Ok(null)
        } else {
            encodeControlPlaneMeta(spec.outputConverter, value, "action", actionName)
        }

    private fun actionExecutor(spec: DeviceActionContract<Any?, Any?>): suspend (Any?) -> Any? {
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

    private fun <I, O> compileAction(spec: DeviceActionContract<I, O>): suspend (I) -> O? {
        val opSpec = operationSpec(OperationKinds.Action)
        val context = OperationContext(OperationKinds.Action, spec.name, spec.descriptor, name)
        val delay = spec.descriptor.timeout ?: opSpec.defaultTimeout
        val retry = spec.descriptor.retryPolicy ?: opSpec.defaultRetry

        val gates = opSpec.gates.map { gate -> suspend { gate.check(context) } }
        val execute = compileOperationExecutor(
            timeout = delay,
            retry = retry,
            gates = gates,
            registry = registry,
            locks = spec.descriptor.requiredLocks,
            timeSource = timeSource,
            observers = { d, f -> opSpec.observers.forEach { try { it.observe(context, d, f) } catch (_: Throwable) {} } },
            terminal = { input: I ->
                catchingOperationOutcome {
                    val argMeta = if (input != null) spec.inputConverter.convert(input) else null
                    val resultMeta = delegate.execute(spec.name, argMeta)
                    if (resultMeta != null) spec.outputConverter.read(resultMeta) else null
                }
            },
        )
        val tracker = delegate as? OperationTracker
        return { input ->
            if (tracker == null) {
                execute(input).getOrThrow()
            } else {
                tracker.enterOperation()
                try {
                    execute(input).getOrThrow()
                } finally {
                    tracker.exitOperation()
                }
            }
        }
    }

    private fun markFailure(cause: RuntimeException): Nothing {
        updateLifecycleState(LifecycleState.Failed(cause))
        throw cause
    }
}

private data class CachedReader(
    val descriptor: space.kscience.krig.api.descriptors.PropertyDescriptor,
    val converter: MetaConverter<*>,
    val reader: TypedReader<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> readerFor(spec: DevicePropertyContract<T>): TypedReader<T> {
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
    fun <T> writerFor(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
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
    fun executorFor(spec: DeviceActionContract<Any?, Any?>): suspend (Any?) -> Any? {
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

private fun unknownProperty(propertyName: Name, operation: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownProperty,
            message = "Cannot $operation property '$propertyName': no DevicePropertyContract is registered.",
        ),
    )

private fun unknownAction(actionName: Name): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownAction,
            message = "Cannot execute action '$actionName': no DeviceActionContract is registered.",
        ),
    )

private fun <T> decodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: Meta,
    kind: String,
    name: Name,
): OperationOutcome<T> {
    try {
        converter.readOrNull(value)?.let { return OperationOutcome.Ok(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OperationFaultException) {
        return OperationOutcome.Fail(e.fault)
    } catch (e: Exception) {
        return invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
    }
    return invalidControlPlanePayload(kind, name, "Payload does not match the registered converter.", null)
}

private fun <T> encodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: T,
    kind: String,
    name: Name,
): OperationOutcome<Meta> = try {
    OperationOutcome.Ok(converter.convert(value))
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    OperationOutcome.Fail(e.fault)
} catch (e: Exception) {
    invalidControlPlanePayload(kind, name, e.message ?: e.toString(), e)
}

private fun invalidControlPlanePayload(
    kind: String,
    name: Name,
    message: String,
    cause: Throwable?,
): OperationOutcome.Fail =
    OperationOutcome.Fail(
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
    )
