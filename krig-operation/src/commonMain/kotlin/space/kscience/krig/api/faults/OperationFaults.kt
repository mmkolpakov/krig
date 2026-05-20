package space.kscience.krig.api.faults

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Local helper for converting `@Serializable` fault instances to [Meta].
 * Duplicates the trivial logic from `meta.serializableToMeta` to avoid a
 * circular dependency: krig-state must not depend on krig-model.
 */
private val faultJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

private fun <T> faultToMeta(serializer: KSerializer<T>, obj: T): Meta =
    faultJson.encodeToJsonElement(serializer, obj).toMeta()

/** Standard krig operation-fault types. Integrations can add their own [Name]s. */
public object OperationFaultTypes {
    public val Generic: Name = "fault.generic".asName()
    public val Validation: Name = "fault.validation".asName()
    public val Timeout: Name = "fault.timeout".asName()
    public val Transport: Name = "fault.transport".asName()
    public val Authorization: Name = "fault.authorization".asName()
    public val InvalidState: Name = "fault.invalid-state".asName()
    public val UnknownProperty: Name = "fault.unknown-property".asName()
    public val UnknownAction: Name = "fault.unknown-action".asName()
    public val UnsupportedValue: Name = "fault.unsupported-value".asName()
    public val System: Name = "fault.system".asName()
    public val FatalSystem: Name = "fault.fatal-system".asName()
}

/**
 * A generic fault implementation for errors that do not have a specialized schema but require
 * structured reporting.
 *
 * @property faultType The machine-readable fault type.
 * @property message A human-readable description of the error.
 * @property details Additional context or debugging information in the form of [Meta].
 */
@Serializable
@SerialName("fault.generic")
public data class GenericOperationFault(
    override val faultType: Name = OperationFaultTypes.Generic,
    override val message: String,
    val details: Meta = Meta.EMPTY
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}

/**
 * A fault indicating that the input provided for an operation failed validation.
 *
 * @property details A [Meta] object containing detailed information about the validation failure.
 */
@Serializable
@SerialName("fault.validation")
public data class ValidationFault(
    val details: Meta,
    override val message: String = OperationFaultTypes.Validation.toString(),
    override val faultType: Name = OperationFaultTypes.Validation,
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}

/**
 * A fault indicating that an operation did not complete within its expected time frame.
 * This is distinct from a network or I/O timeout, representing a business-level timeout.
 */
@Serializable
@SerialName("fault.timeout")
public data class TimeoutFault(
    override val faultType: Name = OperationFaultTypes.Timeout,
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}

/** Transport or I/O failure at an adapter boundary. */
@Serializable
@SerialName("fault.transport")
public data class TransportFault(
    val causeType: String,
    override val message: String,
    val details: Meta = Meta.EMPTY,
    override val faultType: Name = OperationFaultTypes.Transport,
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}

/**
 * A standard fault indicating that an authenticated principal is not authorized to perform an operation.
 *
 * @property principalName The name of the principal who was denied access.
 * @property requiredPermission The permission that was required for the operation.
 */
@Serializable
@SerialName("fault.authorization")
public data class AuthorizationFault(
    val principalName: String = "Unknown",
    val requiredPermission: String = "Unknown",
    override val faultType: Name = OperationFaultTypes.Authorization,
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}

/**
 * A standard fault indicating that an operation was attempted while the device was in an incompatible state.
 *
 * @property currentState The name of the state the device was in.
 * @property requiredState A description of the state(s) required to perform the operation.
 * @property operation The name of the operation that was attempted.
 */
@Serializable
@SerialName("fault.invalid-state")
public data class InvalidStateFault(
    val currentState: String = "Unknown",
    val requiredState: String = "Unknown",
    val operation: String = "Unknown",
    override val faultType: Name = OperationFaultTypes.InvalidState,
) : OperationFault {
    override fun toMeta(): Meta = faultToMeta(serializer(), this)
}
