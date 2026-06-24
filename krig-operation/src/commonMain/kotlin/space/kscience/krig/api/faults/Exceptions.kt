package space.kscience.krig.api.faults

import space.kscience.dataforge.names.Name

/**
 * Base exception for all control-side failures.
 *
 * Represents an *unexpected* system failure. For predictable business errors use
 * [OperationFaultException] with its structured [OperationFault] payload.
 */
public open class OperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Predictable business fault. Used for structured control-flow inside the pipeline
 * (retry loops, gate denials).
 */
public class OperationFaultException(public val fault: OperationFault, cause: Throwable? = null) :
    OperationException("A predictable business fault occurred: ${fault.displayType}: ${fault.message}", cause)

/** Device property access failed. */
public class DevicePropertyException(
    public val name: Name,
    public val property: Name,
    message: String,
    cause: Throwable? = null,
) : OperationException("Property '$property' access on device '$name' failed: $message", cause)
