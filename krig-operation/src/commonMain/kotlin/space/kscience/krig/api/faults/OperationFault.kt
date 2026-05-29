package space.kscience.krig.api.faults

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.toStringUnescaped
import space.kscience.dataforge.meta.MetaRepr

/**
 * Predictable business fault — the negative-but-expected outcome of an operation.
 * Serializable; travels on the wire inside [SerializableOperationFailure].
 * Local code may adapt it to exceptions at interop boundaries.
 */
public interface OperationFault : MetaRepr {
    /** Stable open machine-readable type. Wire-frozen between minor versions. */
    public val faultType: Name

    /** Human-readable description for consoles and logs. Defaults to [faultType]. */
    public val message: String get() = faultType.toString()
}

/** Human-readable fault type without DataForge escaping. */
public val OperationFault.displayType: String get() = faultType.toStringUnescaped()
