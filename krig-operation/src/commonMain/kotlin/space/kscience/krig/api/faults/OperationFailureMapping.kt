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

/**
 * Adapts a throwable to the canonical [OperationFault]. A structured fault from
 * [OperationFaultException] passes through unchanged; any other throwable becomes a
 * [GenericOperationFault] of type [OperationFaultTypes.System] with its cause captured in details.
 */
public fun Throwable.toOperationFault(): OperationFault = when (this) {
    is OperationFaultException -> fault
    else -> {
        val text = message ?: this::class.simpleName ?: "error"
        GenericOperationFault(
            faultType = OperationFaultTypes.System,
            message = text,
            details = faultDetails(message = text, cause = this),
        )
    }
}
