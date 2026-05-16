package space.kscience.krig.api.faults

/**
 * Converts any throwable into a wire-transferable failure payload.
 *
 * The mapper lives outside exception classes so domain exceptions do not depend on the
 * transport DTO shape. [DeviceFaultException] keeps its structured [DeviceFault] payload.
 */
public fun Throwable.toSerializableDeviceFailure(
    includeStackTrace: Boolean = true,
): SerializableDeviceFailure {
    val base = SerializableDeviceFailure(
        type = this::class.simpleName ?: "Throwable",
        message = message ?: "An unknown error occurred.",
        stackTrace = if (includeStackTrace) stackTraceToString() else null,
        cause = cause?.toSerializableDeviceFailure(includeStackTrace),
    )
    return if (this is DeviceFaultException) {
        base.copy(code = fault.code, fault = fault)
    } else {
        base
    }
}
