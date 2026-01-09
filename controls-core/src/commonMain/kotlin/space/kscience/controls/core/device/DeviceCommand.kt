package space.kscience.controls.core.device

import kotlinx.coroutines.CompletableDeferred
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A sealed hierarchy representing all possible commands that can be processed by the [DeviceEntity] actor.
 *
 * Each command carries a [response] deferred to acknowledge completion or report failure back to the caller.
 */
public sealed interface DeviceCommand {
    /**
     * The callback to notify the sender when the command has been processed by the driver.
     * Completes with [Unit] on success, or fails with an exception.
     */
    public val response: CompletableDeferred<Unit>
}

/**
 * Command to write a 64-bit floating point value.
 * Used for [space.kscience.controls.common.tokens.PropertyToken.TYPE_DOUBLE].
 *
 * @property token The pre-resolved property token index (Fast Path).
 * @property value The primitive value to write.
 */
public class WriteDoubleCommand(
    public val token: Int,
    public val value: Double,
    override val response: CompletableDeferred<Unit> = CompletableDeferred()
) : DeviceCommand

/**
 * Command to write a 64-bit integer value.
 * Used for [space.kscience.controls.common.tokens.PropertyToken.TYPE_LONG].
 *
 * @property token The pre-resolved property token index (Fast Path).
 * @property value The primitive value to write.
 */
public class WriteLongCommand(
    public val token: Int,
    public val value: Long,
    override val response: CompletableDeferred<Unit> = CompletableDeferred()
) : DeviceCommand

/**
 * Command to write a boolean value.
 * Used for [space.kscience.controls.common.tokens.PropertyToken.TYPE_BOOLEAN].
 *
 * @property token The pre-resolved property token index (Fast Path).
 * @property value The primitive value to write.
 */
public class WriteBooleanCommand(
    public val token: Int,
    public val value: Boolean,
    override val response: CompletableDeferred<Unit> = CompletableDeferred()
) : DeviceCommand

/**
 * Command to write a structured metadata value.
 * Used for [space.kscience.controls.common.tokens.PropertyToken.TYPE_META].
 *
 * @property token The pre-resolved property token index.
 * @property value The [Meta] object to write.
 */
public class WriteMetaCommand(
    public val token: Int,
    public val value: Meta,
    override val response: CompletableDeferred<Unit> = CompletableDeferred()
) : DeviceCommand

/**
 * Command to execute a named action.
 *
 * @property action The hierarchical name of the action.
 * @property argument Optional input arguments for the action.
 * @property result The deferred that will contain the result of the action (Meta?) or an exception.
 *                Note: This hides the generic [DeviceCommand.response] to provide a typed result.
 */
public class ExecuteActionCommand(
    public val action: Name,
    public val argument: Meta?,
    public val result: CompletableDeferred<Meta?> = CompletableDeferred()
) : DeviceCommand {
    override val response: CompletableDeferred<Unit>
        get() = CompletableDeferred<Unit>().also { proxy ->
            result.invokeOnCompletion { error ->
                if (error != null) proxy.completeExceptionally(error) else proxy.complete(Unit)
            }
        }
}