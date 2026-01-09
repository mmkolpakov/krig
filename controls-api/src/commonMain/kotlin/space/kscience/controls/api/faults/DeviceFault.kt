package space.kscience.controls.api.faults

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.meta.toMeta

/**
 * An interface representing a predictable, non-critical "business fault".
 * This is distinct from a system failure (represented by an exception). A fault is an expected
 * outcome of an operation under certain conditions, such as invalid input or a device being
 * in an incorrect state.
 *
 * Faults are serializable and can be transmitted as part of a regular (though negative)
 * response, allowing clients to handle them gracefully without treating them as unexpected errors.
 * All faults must be representable as [Meta] for interoperability.
 */
public interface DeviceFault : MetaRepr {
    /**
     * A stable, machine-readable error code (e.g., "VALIDATION_ERROR").
     * Used by clients to programmatically react to specific error types.
     */
    public val code: String

    /**
     * A human-readable description of the fault.
     */
    public val message: String

    /**
     * Additional context details.
     */
    public val details: Meta
}

/**
 * A generic container for any fault that is received as pure Meta (e.g. from a remote device).
 */
@Serializable
public data class GenericFault(
    override val code: String,
    override val message: String,
    override val details: Meta = Meta.EMPTY
) : DeviceFault {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}