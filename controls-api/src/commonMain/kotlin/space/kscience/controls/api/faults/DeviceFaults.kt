package space.kscience.controls.api.faults

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toMeta

private val faultJson = Json { ignoreUnknownKeys = true }

private fun <T> serializableToMeta(serializer: KSerializer<T>, value: T): Meta =
    faultJson.encodeToJsonElement(serializer, value).toMeta()

/**
 * A generic fault implementation for errors that do not have a specialized schema but require
 * structured reporting.
 *
 * @property code The machine-readable error code.
 * @property message A human-readable description of the error.
 * @property details Additional context or debugging information in the form of [Meta].
 */
@Serializable
@SerialName("fault.generic")
public data class GenericDeviceFault(
    override val code: String,
    val message: String,
    val details: Meta = Meta.EMPTY
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A fault indicating that the input provided for an operation failed validation.
 *
 * @property details A [Meta] object containing detailed information about the validation failure.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "VALIDATION_ERROR".
 */
@Serializable
@SerialName("fault.validation")
public data class ValidationFault(
    val details: Meta,
    override val code: String = "VALIDATION_ERROR",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A fault indicating that a required precondition for an operation was not met.
 *
 * @property message A human-readable message explaining which precondition failed.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "PRECONDITION_FAILED".
 */
@Serializable
@SerialName("fault.precondition")
public data class PreconditionFault(
    val message: String = "A precondition for the operation was not met.",
    override val code: String = "PRECONDITION_FAILED",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A fault indicating that a required resource (e.g., a physical port, a file lock)
 * is currently in use by another operation and cannot be accessed.
 *
 * @property code A stable, machine-readable identifier for this fault type. Value is "RESOURCE_BUSY".
 */
@Serializable
@SerialName("fault.resourceBusy")
public data class ResourceBusyFault(
    override val code: String = "RESOURCE_BUSY",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A fault indicating that an operation did not complete within its expected time frame.
 * This is distinct from a network or I/O timeout, representing a business-level timeout.
 *
 * @property code A stable, machine-readable identifier for this fault type. Value is "TIMEOUT".
 */
@Serializable
@SerialName("fault.timeout")
public data class TimeoutFault(
    override val code: String = "TIMEOUT",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard fault indicating that a requested resource (e.g., a device, property, or action) could not be found.
 *
 * @property resourceType A string describing the type of resource that was not found.
 * @property resourceId A string identifier for the missing resource.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "NOT_FOUND".
 */
@Serializable
@SerialName("fault.notFound")
public data class NotFoundFault(
    val resourceType: String = "Unknown",
    val resourceId: String = "Unknown",
    override val code: String = "NOT_FOUND",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard fault indicating that an authentication attempt failed.
 *
 * @property reason A human-readable message explaining why authentication failed.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "AUTHENTICATION_FAILED".
 */
@Serializable
@SerialName("fault.authentication")
public data class AuthenticationFault(
    val reason: String = "Authentication failed.",
    override val code: String = "AUTHENTICATION_FAILED",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard fault indicating that an authenticated principal is not authorized to perform an operation.
 *
 * @property principalName The name of the principal who was denied access.
 * @property requiredPermission The permission that was required for the operation.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "AUTHORIZATION_DENIED".
 */
@Serializable
@SerialName("fault.authorization")
public data class AuthorizationFault(
    val principalName: String = "Unknown",
    val requiredPermission: String = "Unknown",
    override val code: String = "AUTHORIZATION_DENIED",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard fault indicating that an operation was attempted while the device was in an incompatible state.
 *
 * @property currentState The name of the state the device was in.
 * @property requiredState A description of the state(s) required to perform the operation.
 * @property operation The name of the operation that was attempted.
 * @property code A stable, machine-readable identifier for this fault type. Defaults to "INVALID_STATE".
 */
@Serializable
@SerialName("fault.invalidState")
public data class InvalidStateFault(
    val currentState: String = "Unknown",
    val requiredState: String = "Unknown",
    val operation: String = "Unknown",
    override val code: String = "INVALID_STATE",
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}