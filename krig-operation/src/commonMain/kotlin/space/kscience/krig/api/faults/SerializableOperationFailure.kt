package space.kscience.krig.api.faults

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * Wire-transferable failure payload. A non-null [fault] marks a predictable business
 * fault; `null` means a system exception captured via [type] / [message] / [stackTrace].
 */
@Serializable
public data class SerializableOperationFailure(
    val type: String,
    val message: String,
    val stackTrace: String? = null,
    val details: Meta = Meta.EMPTY,
    val faultType: Name? = null,
    val retryable: Boolean = false,
    val fault: OperationFault? = null,
    val cause: SerializableOperationFailure? = null,
)
