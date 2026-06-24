package space.kscience.krig.api.faults

/**
 * Cause chains beyond this depth are truncated with a marker entry: pathological chains
 * (cycles, artificially deep nesting) must not overflow the stack on the error-serialization path.
 */
private const val MAX_CAUSE_DEPTH: Int = 16

/**
 * Converts any throwable into a wire-transferable failure payload.
 *
 * The mapper lives outside exception classes so domain exceptions do not depend on the
 * transport DTO shape. [OperationFaultException] keeps its structured [OperationFault] payload.
 * Cause chains are capped at [MAX_CAUSE_DEPTH] entries.
 */
public fun Throwable.toSerializableOperationFailure(
    includeStackTrace: Boolean = true,
): SerializableOperationFailure = toSerializableOperationFailure(includeStackTrace, MAX_CAUSE_DEPTH)

private fun Throwable.toSerializableOperationFailure(
    includeStackTrace: Boolean,
    remainingDepth: Int,
): SerializableOperationFailure {
    if (remainingDepth <= 0) {
        return SerializableOperationFailure(
            type = "TruncatedCauseChain",
            message = "Cause chain exceeds $MAX_CAUSE_DEPTH entries and was truncated.",
            stackTrace = null,
            cause = null,
        )
    }
    val base = SerializableOperationFailure(
        type = this::class.simpleName ?: "Throwable",
        message = message ?: "An unknown error occurred.",
        stackTrace = if (includeStackTrace) stackTraceToString() else null,
        cause = cause?.toSerializableOperationFailure(includeStackTrace, remainingDepth - 1),
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
