package space.kscience.controls.api.faults

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta

/**
 * A serializable representation of a device failure, suitable for transmission over the network or between processes.
 * This structure captures the essential information about an exception without relying on platform-specific serialization.
 * It can represent both unexpected system failures and predictable business faults.
 *
 * @property type The simple class name of the original exception, used for identification.
 * @property message The descriptive message of the failure.
 * @property stackTrace An optional string representation of the stack trace, useful for remote debugging of system failures.
 * @property details Additional context-specific details about the error, provided as a [Meta] object.
 * @property code An optional, machine-readable error code (e.g., "E-1024", "TIMEOUT").
 * @property retryable A flag indicating whether the operation that caused this failure can be safely retried.
 * @property fault If non-null, this indicates that the failure was a predictable business fault, not a system error.
 *                 Clients can use the presence of this field to handle the outcome as a valid negative response
 *                 rather than an unexpected exception.
 * @property cause An optional serializable representation of the underlying cause, allowing for nested error reporting.
 */
@Serializable
public data class SerializableDeviceFailure(
    val type: String,
    val message: String,
    val stackTrace: String? = null,
    val details: Meta = Meta.EMPTY,
    val code: String? = null,
    val retryable: Boolean = false,
    val fault: DeviceFault? = null,
    val cause: SerializableDeviceFailure? = null,
)