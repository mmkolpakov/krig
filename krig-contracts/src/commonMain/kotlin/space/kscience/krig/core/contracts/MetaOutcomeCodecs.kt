package space.kscience.krig.core.contracts

import kotlinx.coroutines.CancellationException
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.InternalKrigApi

/**
 * The single Meta↔typed boundary codec shared by the typed backend builder and the pipeline
 * control plane. Keeping one implementation guarantees both surfaces fail identically on the same
 * bad payload: decoding problems become [ValidationFault] outcomes, never lifecycle failures.
 */
@InternalKrigApi
public fun <T> decodeMetaOutcome(
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
        return invalidPayload(kind, name, e.message ?: e.toString(), e)
    }
    return invalidPayload(kind, name, "Payload does not match the registered converter.", null)
}

/** Encoding counterpart of [decodeMetaOutcome]. */
@InternalKrigApi
public fun <T> encodeMetaOutcome(
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
    invalidPayload(kind, name, e.message ?: e.toString(), e)
}

private fun invalidPayload(
    kind: String,
    name: Name,
    message: String,
    cause: Throwable?,
): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            details = faultDetails(message = message, kind = kind, name = name, cause = cause),
        ),
    )
