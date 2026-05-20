package space.kscience.krig.api.faults

/**
 * Converts any throwable into a wire-transferable failure payload.
 *
 * The mapper lives outside exception classes so domain exceptions do not depend on the
 * transport DTO shape. [OperationFaultException] keeps its structured [OperationFault] payload.
 */
public fun Throwable.toSerializableOperationFailure(
    includeStackTrace: Boolean = true,
): SerializableOperationFailure {
    val base = SerializableOperationFailure(
        type = this::class.simpleName ?: "Throwable",
        message = message ?: "An unknown error occurred.",
        stackTrace = if (includeStackTrace) stackTraceToString() else null,
        cause = cause?.toSerializableOperationFailure(includeStackTrace),
    )
    return if (this is OperationFaultException) {
        base.copy(faultType = fault.faultType, fault = fault)
    } else {
        base
    }
}
