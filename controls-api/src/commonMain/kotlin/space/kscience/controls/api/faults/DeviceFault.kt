package space.kscience.controls.api.faults

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
     * This code is not intended for display to the user but for use in client-side logic
     * to reliably identify the type of fault. It should not change between minor versions.
     */
    public val code: String
}
