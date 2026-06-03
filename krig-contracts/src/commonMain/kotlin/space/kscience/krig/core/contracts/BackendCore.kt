package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.operationFault
import space.kscience.krig.api.faults.validationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import kotlin.time.Duration

internal typealias MetaReader = suspend (DeviceEnvironment) -> Meta
internal typealias ObservedMetaReader = suspend (DeviceEnvironment) -> ObservedValue<Meta?>
internal typealias BinaryReader = suspend (DeviceEnvironment) -> Binary
internal typealias MetaWriter = suspend (DeviceEnvironment, Meta) -> OperationOutcome<Unit>
internal typealias MetaAction = suspend (DeviceEnvironment, Meta?) -> OperationOutcome<Meta?>

internal typealias BatchObservedBody =
    suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>
internal typealias BatchMetaBody =
    suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Meta>>
internal typealias BatchBinaryBody =
    suspend DeviceEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>
internal typealias BatchWriteBody =
    suspend DeviceEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>

// Lower per-entry handler maps into the env-aware shapes [BackendCore] dispatches on. The handlers
// ignore the DeviceEnvironment unless an engine (e.g. the device DSL) needs it.

internal fun <E> Map<Name, E>.toMetaReaders(read: suspend (E) -> Meta): Map<Name, MetaReader> =
    mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toObservedReaders(
    read: suspend (E) -> ObservedValue<Meta?>,
): Map<Name, ObservedMetaReader> = mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toBinaryReaders(read: suspend (E) -> Binary): Map<Name, BinaryReader> =
    mapValues { (_, entry) -> { read(entry) } }

internal fun <E> Map<Name, E>.toMetaWriters(
    write: suspend (E, Meta) -> OperationOutcome<Unit>,
): Map<Name, MetaWriter> = mapValues { (_, entry) -> { _, value -> write(entry, value) } }

internal fun <E> Map<Name, E>.toMetaActions(
    execute: suspend (Name, E, Meta?) -> OperationOutcome<Meta?>,
): Map<Name, MetaAction> = mapValues { (name, entry) -> { _, argument -> execute(name, entry, argument) } }

/**
 * Lowered backend behaviour keyed by [Name]: per-property read / write / action handlers plus
 * optional whole-backend coalescing hooks. Builders fill this and hand it to [BackendCore]; handlers
 * receive the per-operation [DeviceEnvironment] and may ignore it.
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
 * Single [DeviceBackend] implementation shared by every builder.
 *
 * Each builder lowers its typed handles, cell properties, or DSL blocks into [BackendHandlers]; this
 * core owns the one read / write / execute / batch implementation so the engines never re-derive
 * dispatch, fault vocabulary, or batch fallbacks.
 */
@OptIn(UnstableKrigForSubclassing::class)
internal class BackendCore(private val handlers: BackendHandlers) : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> {
        handlers.metaReaders[property.name]?.let { reader -> return runCatchingOperation { reader(device) } }
        handlers.observedReaders[property.name]?.let { reader ->
            return when (val outcome = runCatchingOperation { reader(device) }) {
                is OperationOutcome.Ok -> outcome.value.value?.let { OperationOutcome.Ok(it) }
                    ?: validationFault("Observed property '${property.name}' has no Meta value")
                is OperationOutcome.Fail -> outcome
            }
        }
        return operationFault(OperationFaultTypes.UnknownProperty, "Unknown property '${property.name}'")
    }

    context(device: DeviceEnvironment)
    override suspend fun readObserved(property: PropertyDescriptor): OperationOutcome<ObservedValue<Meta?>> {
        handlers.observedReaders[property.name]?.let { reader -> return runCatchingOperation { reader(device) } }
        return read(property).toObserved(device)
    }

    context(device: DeviceEnvironment)
    override suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> {
        handlers.binaryReaders[property.name]?.let { reader -> return runCatchingOperation { reader(device) } }
        return operationFault(OperationFaultTypes.UnsupportedValue, "Property '${property.name}' has no binary reader")
    }

    context(device: DeviceEnvironment)
    override suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        handlers.batchObserved?.let { return it.invoke(device, properties) }
        handlers.batchMeta?.let { body ->
            return body.invoke(device, properties).mapValues { (_, outcome) -> outcome.toObserved(device) }
        }
        return properties.associate { property -> property.name to readObserved(property) }
    }

    context(device: DeviceEnvironment)
    override suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> {
        handlers.batchBinary?.let { return it.invoke(device, properties) }
        return properties.associate { property -> property.name to readBinary(property) }
    }

    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> =
        handlers.writers[property.name]?.invoke(device, value)
            ?: validationFault("Property '${property.name}' is not writable")

    context(device: DeviceEnvironment)
    override suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> {
        handlers.batchWrite?.let { return it.invoke(device, values) }
        return buildMap(values.size) {
            for ((property, value) in values) put(property.name, write(property, value))
        }
    }

    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> =
        handlers.actions[action.name]?.invoke(device, argument)
            ?: operationFault(OperationFaultTypes.UnknownAction, "Unknown action '${action.name}'")

    override fun close() {
        handlers.onClose?.invoke()
    }

    private fun OperationOutcome<Meta>.toObserved(device: DeviceEnvironment): OperationOutcome<ObservedValue<Meta?>> =
        when (this) {
            is OperationOutcome.Ok -> OperationOutcome.Ok(ObservedValue(value, device.clock.now(), DataQuality.GOOD))
            is OperationOutcome.Fail -> this
        }
}

/**
 * Adapts any [DeviceBackend] into a [SteppedBackend] by attaching a [step] body, so a simulation
 * scheduler can advance it on every tick. State-less backends keep their plain [DeviceBackend] type.
 */
@OptIn(UnstableKrigForSubclassing::class)
public fun SteppedBackend(backend: DeviceBackend, step: (Duration) -> Unit): SteppedBackend =
    SteppingBackend(backend, step)

@OptIn(UnstableKrigForSubclassing::class)
private class SteppingBackend(
    backend: DeviceBackend,
    private val stepBody: (Duration) -> Unit,
) : SteppedBackend, DeviceBackend by backend {
    override fun step(dt: Duration) = stepBody(dt)
}

@Suppress("UNCHECKED_CAST")
internal fun MetaConverter<*>.convertAny(value: Any?): Meta =
    (this as MetaConverter<Any?>).convert(value)
