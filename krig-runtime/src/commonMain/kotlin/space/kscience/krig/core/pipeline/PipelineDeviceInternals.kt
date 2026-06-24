@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.OperationTracker
import space.kscience.krig.core.contracts.trackReentrant
import space.kscience.krig.core.contracts.decodeMetaOutcome
import space.kscience.krig.core.contracts.encodeMetaOutcome
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

// Private plumbing extracted from PipelineDevice: erased casts, the compiled-once operation
// caches, the control-plane Meta codec and the fault constructors. Kept `internal` so the
// public decorator stays a thin shell over these building blocks.

internal fun batchDescriptor(name: Name): PropertyDescriptor =
    PropertyDescriptor(
        name = name,
        kind = PropertyKind.LOGICAL,
        valueTypeId = TypeIds.META,
    )

@Suppress("UNCHECKED_CAST")
internal fun <T> OperationOutcome<Any?>.castOutcome(): OperationOutcome<T> =
    this as OperationOutcome<T>

@Suppress("UNCHECKED_CAST")
internal fun <T> OperationOutcome<T>.eraseOutcome(): OperationOutcome<Any?> =
    this as OperationOutcome<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> Any?.castPayload(): T = this as T

@Suppress("UNCHECKED_CAST")
internal fun <T> TypedReader<T>.outcomeReaderOrNull(): OutcomeTypedReader<T>? =
    this as? OutcomeTypedReader<T>

@Suppress("UNCHECKED_CAST")
internal fun <T> TypedWriter<T>.outcomeWriterOrNull(): OutcomeTypedWriter<T>? =
    this as? OutcomeTypedWriter<T>

@Suppress("UNCHECKED_CAST")
internal fun <I, O> TypedAction<I, O>.outcomeActionOrNull(): OutcomeTypedAction<I, O>? =
    this as? OutcomeTypedAction<I, O>

@Suppress("UNCHECKED_CAST")
internal fun DevicePropertyContract<*>.asAnyPropertyContract(): DevicePropertyContract<Any?> =
    this as DevicePropertyContract<Any?>

@Suppress("UNCHECKED_CAST")
internal fun MutableDevicePropertyContract<*>.asAnyMutablePropertyContract(): MutableDevicePropertyContract<Any?> =
    this as MutableDevicePropertyContract<Any?>

@Suppress("UNCHECKED_CAST")
internal fun DeviceActionContract<*, *>.asAnyActionContract(): DeviceActionContract<Any?, Any?> =
    this as DeviceActionContract<Any?, Any?>

@Suppress("UNCHECKED_CAST")
internal fun <I, O> (suspend (Any?) -> Any?).castActionExecutor(): suspend (I) -> O? =
    this as suspend (I) -> O?

internal suspend fun <T> trackedOperation(
    tracker: OperationTracker?,
    block: suspend () -> OperationOutcome<T>,
): OperationOutcome<T> =
    tracker?.trackReentrant(block) ?: block()

internal data class CachedReader(
    val descriptor: PropertyDescriptor,
    val converter: MetaConverter<*>,
    val reader: TypedReader<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> readerFor(spec: DevicePropertyContract<T>): TypedReader<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return reader as TypedReader<T>
    }
}

internal interface OutcomeTypedReader<T> : TypedReader<T> {
    suspend fun readOutcome(): OperationOutcome<T>
}

internal interface OutcomeTypedWriter<T> : TypedWriter<T> {
    suspend fun writeOutcome(value: T): OperationOutcome<Unit>
}

internal interface OutcomeTypedAction<I, O> : TypedAction<I, O> {
    suspend fun executeOutcome(input: I): OperationOutcome<O?>
}

internal data class CachedWriter(
    val descriptor: PropertyDescriptor,
    val converter: MetaConverter<*>,
    val writer: TypedWriter<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> writerFor(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        requireCompatible(spec.descriptor, spec.converter, spec.name)
        return writer as TypedWriter<T>
    }
}

internal data class CachedAction(
    val descriptor: ActionDescriptor,
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
    descriptor: PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

private fun CachedWriter.requireCompatible(
    descriptor: PropertyDescriptor,
    converter: MetaConverter<*>,
    name: Name,
) {
    check(this.descriptor == descriptor && this.converter === converter) {
        "Property '$name' was requested with a different descriptor or converter instance."
    }
}

internal fun unknownProperty(propertyName: Name, operation: String): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownProperty,
            message = when (operation) {
                // A read-only property has a contract — just not a mutable one; say so explicitly.
                "write" ->
                    "Cannot write property '$propertyName': no mutable DevicePropertyContract is registered " +
                            "(the property may be read-only)."
                else -> "Cannot $operation property '$propertyName': no DevicePropertyContract is registered."
            },
        ),
    )

internal fun unknownAction(actionName: Name): OperationOutcome.Fail =
    OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownAction,
            message = "Cannot execute action '$actionName': no DeviceActionContract is registered.",
        ),
    )

// Single shared Meta↔typed codec (krig-contracts) — the typed builder and the control plane must
// fail identically on the same bad payload.
internal fun <T> decodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: Meta,
    kind: String,
    name: Name,
): OperationOutcome<T> = decodeMetaOutcome(converter, value, kind, name)

internal fun <T> encodeControlPlaneMeta(
    converter: MetaConverter<T>,
    value: T,
    kind: String,
    name: Name,
): OperationOutcome<Meta> = encodeMetaOutcome(converter, value, kind, name)
