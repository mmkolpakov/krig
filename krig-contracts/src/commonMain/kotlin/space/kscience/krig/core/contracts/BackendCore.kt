package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.UnstableKrigForSubclassing
import kotlin.time.Duration

internal typealias MetaReader = suspend (BackendEnvironment) -> Meta
internal typealias ObservedMetaReader = suspend (BackendEnvironment) -> ObservedValue<Meta?>
internal typealias BinaryReader = suspend (BackendEnvironment) -> Binary
internal typealias MetaWriter = suspend (BackendEnvironment, Meta) -> Unit
internal typealias MetaAction = suspend (BackendEnvironment, Meta?) -> Meta?

internal typealias BatchObservedBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>
internal typealias BatchMetaBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Meta>>
internal typealias BatchBinaryBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>
internal typealias BatchWriteBody =
        suspend BackendEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>

// Lower per-entry handler maps into the env-aware shapes [BoundBackendCore] dispatches on.

internal fun <E> Map<Name, E>.toMetaReaders(read: suspend (E) -> Meta): Map<Name, MetaReader> =
    mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toObservedReaders(
    read: suspend (E) -> ObservedValue<Meta?>,
): Map<Name, ObservedMetaReader> = mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toBinaryReaders(read: suspend (E) -> Binary): Map<Name, BinaryReader> =
    mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toMetaWriters(
    write: suspend (E, Meta) -> Unit,
): Map<Name, MetaWriter> = mapValues { (_, entry) -> { _, value -> write(entry, value) } }

internal fun <E> Map<Name, E>.toMetaActions(
    execute: suspend (Name, E, Meta?) -> Meta?,
): Map<Name, MetaAction> = mapValues { (name, entry) -> { _, argument -> execute(name, entry, argument) } }

/**
 * Lowered backend behaviour keyed by [Name]: per-property read / write / action handlers plus
 * optional whole-backend coalescing hooks. Builders fill this and hand it to [BackendCore].
 */
internal class BackendHandlers(
    val metaReaders: Map<Name, MetaReader> = emptyMap(),
    val observedReaders: Map<Name, ObservedMetaReader> = emptyMap(),
    val binaryReaders: Map<Name, BinaryReader> = emptyMap(),
    val writers: Map<Name, MetaWriter> = emptyMap(),
    val actions: Map<Name, MetaAction> = emptyMap(),
    val batchObserved: BatchObservedBody? = null,
    val batchMeta: BatchMetaBody? = null,
    val batchBinary: BatchBinaryBody? = null,
    val batchWrite: BatchWriteBody? = null,
    val onClose: (() -> Unit)? = null,
)

/**
 * Backend factory shared by every builder. Binding captures the concrete device environment and
 * produces the raw operation surface used by [BackendDevice].
 */
@OptIn(UnstableKrigForSubclassing::class)
internal class BackendCore(private val handlers: BackendHandlers) : DeviceBackend {
    override fun bind(environment: BackendEnvironment): BoundDeviceBackend =
        BoundBackendCore(environment, handlers)

    override fun close() {
        handlers.onClose?.invoke()
    }
}

private class BoundBackendCore(
    override val environment: BackendEnvironment,
    private val handlers: BackendHandlers,
) : BoundDeviceBackend {
    override suspend fun read(property: PropertyDescriptor): Meta {
        handlers.metaReaders[property.name]?.let { reader -> return reader(environment) }
        handlers.observedReaders[property.name]?.let { reader ->
            return reader(environment).value
                ?: validationFailure("Observed property '${property.name}' has no Meta value")
        }
        unknownProperty(property.name)
    }

    override suspend fun readObserved(property: PropertyDescriptor): ObservedValue<Meta?> {
        handlers.observedReaders[property.name]?.let { reader -> return reader(environment) }
        return ObservedValue(read(property), environment.clock.now(), DataQuality.GOOD)
    }

    override suspend fun readBinary(property: PropertyDescriptor): Binary {
        handlers.binaryReaders[property.name]?.let { reader -> return reader(environment) }
        unsupported("Property '${property.name}' has no binary reader")
    }

    override suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        handlers.batchObserved?.let { return it.invoke(environment, properties) }
        handlers.batchMeta?.let { body ->
            return body.invoke(environment, properties).mapValues { (_, outcome) -> outcome.toObserved(environment) }
        }
        return super.readBatchObserved(properties)
    }

    override suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> {
        handlers.batchBinary?.let { return it.invoke(environment, properties) }
        return super.readBatchBinary(properties)
    }

    override suspend fun write(property: PropertyDescriptor, value: Meta) {
        val writer = handlers.writers[property.name]
            ?: validationFailure("Property '${property.name}' is not writable")
        writer(environment, value)
    }

    override suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> {
        handlers.batchWrite?.let { return it.invoke(environment, values) }
        return super.writeBatch(values)
    }

    override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta? =
        handlers.actions[action.name]?.invoke(environment, argument)
            ?: unknownAction(action.name)

    override fun close() {
        handlers.onClose?.invoke()
    }

    private fun OperationOutcome<Meta>.toObserved(device: BackendEnvironment): OperationOutcome<ObservedValue<Meta?>> =
        when (this) {
            is OperationOutcome.Ok -> OperationOutcome.Ok(ObservedValue(value, device.clock.now(), DataQuality.GOOD))
            is OperationOutcome.Fail -> this
        }
}

/**
 * Adapts any [DeviceBackend] into an in-process [SteppedBackend] by attaching a [step] body.
 */
@OptIn(UnstableKrigForSubclassing::class)
public fun SteppedBackend(backend: DeviceBackend, step: (Duration) -> Unit): SteppedBackend =
    SteppingBackend(backend, step)

@OptIn(UnstableKrigForSubclassing::class)
private class SteppingBackend(
    private val backend: DeviceBackend,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend, DeviceBackend by backend {
    override fun step(dt: Duration) = stepBody(dt)
}

@Suppress("UNCHECKED_CAST")
internal fun MetaConverter<*>.convertAny(value: Any?): Meta =
    (this as MetaConverter<Any?>).convert(value)

internal fun unsupported(message: String): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnsupportedValue,
            message = message,
        ),
    )

internal fun unknownProperty(name: Name): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownProperty,
            message = "Unknown property '$name'.",
        ),
    )

internal fun unknownAction(name: Name): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownAction,
            message = "Unknown action '$name'.",
        ),
    )

internal fun validationFailure(message: String, property: Name? = null): Nothing =
    throw OperationFaultException(
        ValidationFault(
            details = faultDetails(message, property = property),
        ),
    )

internal fun OperationOutcome.Fail.throwFault(): Nothing =
    throw OperationFaultException(fault)

internal fun OperationOutcome<Unit>.getOrThrowUnit(): Unit {
    getOrThrow()
}
