package space.kscience.krig.api.faults

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toMeta

/**
 * Local helper for converting `@Serializable` fault instances to [Meta].
 * Duplicates the trivial logic from `meta.serializableToMeta` to avoid a
 * circular dependency: krig-state must not depend on krig-model.
 */
private val faultJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

private fun <T> faultToMeta(serializer: KSerializer<T>, obj: T): Meta =
    faultJson.encodeToJsonElement(serializer, obj).toMeta()

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
    override val message: String,
    val details: Meta = Meta.EMPTY
) : DeviceFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
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
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
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
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
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
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
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
@SerialName("fault.invalid-state")
public data class InvalidStateFault(
    val currentState: String = "Unknown",
    val requiredState: String = "Unknown",
    val operation: String = "Unknown",
    override val code: String = "INVALID_STATE",
) : DeviceFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}