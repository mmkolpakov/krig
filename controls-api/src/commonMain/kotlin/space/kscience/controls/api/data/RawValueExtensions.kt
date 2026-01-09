package space.kscience.controls.api.data

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.boolean
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.long

/**
 * Safely coerces a [RawValue] to a Double.
 *
 * Use cases:
 * - A device expects a Double setpoint, but the JSON command contained an Integer (e.g. `{"val": 42}`).
 * - A Boolean flag needs to be logged as 1.0/0.0.
 *
 * @return The double representation, or [Double.NaN] if conversion is impossible.
 */
public fun RawValue.coerceToDouble(): Double = when (this) {
    is RawValue.D -> value
    is RawValue.F -> value.toDouble()
    is RawValue.I -> value.toDouble()
    is RawValue.L -> value.toDouble()
    is RawValue.B -> if (value) 1.0 else 0.0
    // Unsigned
    is RawValue.UB -> value.toDouble()
    is RawValue.US -> value.toDouble()
    is RawValue.UI -> value.toDouble()
    is RawValue.UL -> value.toDouble() // Possible precision loss for very large ULongs
    // Parsing
    is RawValue.S -> value.toDoubleOrNull() ?: Double.NaN
    is RawValue.M -> value.double ?: Double.NaN
    // Fallback
    else -> Double.NaN
}

/**
 * Safely coerces a [RawValue] to a Long.
 *
 * Use cases:
 * - Setting registers that accept integer bitmasks.
 * - converting timestamps.
 *
 * @return The long representation. Returns `0L` if conversion fails (check your logic if this is acceptable,
 * or use logic to validate before write).
 */
public fun RawValue.coerceToLong(): Long = when (this) {
    is RawValue.L -> value
    is RawValue.I -> value.toLong()
    is RawValue.B -> if (value) 1L else 0L
    // Floating point truncation
    is RawValue.D -> value.toLong()
    is RawValue.F -> value.toLong()
    // Unsigned
    is RawValue.UB -> value.toLong()
    is RawValue.US -> value.toLong()
    is RawValue.UI -> value.toLong()
    is RawValue.UL -> value.toLong()
    // Parsing
    is RawValue.S -> value.toLongOrNull() ?: 0L
    is RawValue.M -> value.long ?: 0L
    else -> 0L
}

/**
 * Safely coerces a [RawValue] to a Boolean.
 *
 * Logic:
 * - Numbers: != 0 is true.
 * - Strings: "true", "1", "on" (case-insensitive) are true.
 */
public fun RawValue.coerceToBoolean(): Boolean = when (this) {
    is RawValue.B -> value
    is RawValue.I -> value != 0
    is RawValue.L -> value != 0L
    is RawValue.D -> value != 0.0
    is RawValue.F -> value != 0.0f
    is RawValue.S -> when (value.lowercase()) {
        "true", "1", "on", "yes" -> true
        else -> false
    }
    is RawValue.M -> value.boolean ?: false
    else -> false
}

/**
 * Wraps any Kotlin object into a [RawValue].
 * This is a helper for the "Slow Path" (Commands/Events).
 *
 * **Performance Warning:** This method performs allocation and boxing. Do not use in tight loops.
 */
public fun RawValue.Companion.of(value: Any?): RawValue = when (value) {
    null -> RawValue.M(Meta.EMPTY) // Or specialized Null type if added
    is RawValue -> value
    is Double -> RawValue.D(value)
    is Float -> RawValue.F(value)
    is Int -> RawValue.I(value)
    is Long -> RawValue.L(value)
    is Boolean -> RawValue.B(value)
    is String -> RawValue.S(value)
    is ByteArray -> RawValue.Bin(value)
    is DoubleArray -> RawValue.DArr(value)
    is IntArray -> RawValue.IArr(value)
    is Meta -> RawValue.M(value)
    else -> RawValue.S(value.toString()) // Fallback to string representation
}