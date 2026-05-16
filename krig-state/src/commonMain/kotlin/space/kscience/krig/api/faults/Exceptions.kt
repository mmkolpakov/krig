package space.kscience.krig.api.faults

import space.kscience.dataforge.names.Name

/**
 * Base exception for all control-side failures.
 *
 * Represents an *unexpected* system failure. For predictable business errors use
 * [DeviceFaultException] with its structured [DeviceFault] payload.
 */
public open class DeviceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Multiplatform-safe exception for security errors. Thrown by services like
 * `AuthorizationService` when a [Principal][space.kscience.krig.api.context.Principal]
 * lacks the required [Permission][space.kscience.krig.api.identifiers.Permission].
 */
public class DeviceSecurityException(message: String, cause: Throwable? = null) : DeviceException(message, cause)

/**
 * Predictable business fault. Used for structured control-flow inside the pipeline
 * (retry loops, gate denials).
 */
public class DeviceFaultException(public val fault: DeviceFault, cause: Throwable? = null) :
    DeviceException("A predictable business fault occurred: ${fault::class.simpleName}", cause)

/** Device property access failed. */
public class DevicePropertyException(
    public val name: Name,
    public val property: Name,
    message: String,
    cause: Throwable? = null,
) : DeviceException("Property '$property' access on device '$name' failed: $message", cause)
