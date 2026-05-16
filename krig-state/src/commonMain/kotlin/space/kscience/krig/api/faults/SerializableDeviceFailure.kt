package space.kscience.krig.api.faults

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta

/**
 * Wire-transferable failure payload. A non-null [fault] marks a predictable business
 * fault; `null` means a system exception captured via [type] / [message] / [stackTrace].
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