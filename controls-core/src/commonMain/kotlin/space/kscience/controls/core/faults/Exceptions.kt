package space.kscience.controls.core.faults

import space.kscience.controls.api.faults.DeviceFault
import space.kscience.controls.api.faults.SerializableDeviceFailure
import space.kscience.dataforge.names.Name

/**
 * A base exception for all control operations within the framework.
 *
 * @param message A descriptive message of the failure.
 * @param cause The underlying exception that caused this hub exception, if any.
 */
public open class CompositeHubException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /**
     * Converts this exception into a serializable failure representation for network transmission.
     */
    public open fun toSerializableFailure(): SerializableDeviceFailure = SerializableDeviceFailure(
        type = this::class.simpleName ?: "CompositeHubException",
        message = message ?: "An unknown error occurred.",
        stackTrace = this.stackTraceToString(),
        cause = (cause as? CompositeHubException)?.toSerializableFailure()
    )
}

/**
 * A specialized exception used to signal a predictable business fault (e.g. Validation Error).
 * This indicates a valid but negative result of an operation.
 */
public class DeviceFaultException(public val fault: DeviceFault, cause: Throwable? = null) :
    CompositeHubException("A predictable business fault occurred: ${fault::class.simpleName}", cause) {

    override fun toSerializableFailure(): SerializableDeviceFailure =
        super.toSerializableFailure().copy(fault = this.fault)
}

/**
 * Thrown when an operation cannot be completed because the target device is missing.
 */
public class DeviceNotFoundInCompositeHubException(public val name: Name) :
    CompositeHubException("Device with name '$name' not found in the hub.")

/**
 * Thrown when a device fails to transition states (e.g. startup timeout).
 */
public class DeviceLifecycleException(public val name: Name, message: String, cause: Throwable? = null) :
    CompositeHubException("Lifecycle operation for device '$name' failed: $message", cause)

/**
 * Thrown during property access errors.
 */
public class DevicePropertyException(
    public val name: Name,
    public val property: Name,
    message: String,
    cause: Throwable? = null
) : CompositeHubException("Property '$property' access on device '$name' failed: $message", cause)

/**
 * Thrown when an action execution fails.
 */
public class DeviceActionException(
    public val name: Name,
    public val action: Name,
    message: String,
    cause: Throwable? = null
) : CompositeHubException("Action '$action' execution on device '$name' failed: $message", cause)

/**
 * Thrown by Authorization services.
 */
public class DeviceSecurityException(message: String, cause: Throwable? = null) : CompositeHubException(message, cause)