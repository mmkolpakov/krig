package space.kscience.krig.storage.timeseries

import space.kscience.kmath.structures.Float64Buffer

/** Arithmetic mean of the buffer, or `NaN` when empty. */
public fun Float64Buffer.mean(): Double {
    if (size == 0) return Double.NaN
    var sum = 0.0
    for (i in 0 until size) sum += this[i]
    return sum / size
}

/** Smallest sample, ignoring `NaN`, or `NaN` when empty or all-`NaN`. */
public fun Float64Buffer.minOrNaN(): Double {
    var result = Double.NaN
    for (i in 0 until size) {
        val v = this[i]
        if (!v.isNaN() && (result.isNaN() || v < result)) result = v
    }
    return result
}

/** Largest sample, ignoring `NaN`, or `NaN` when empty or all-`NaN`. */
public fun Float64Buffer.maxOrNaN(): Double {
    var result = Double.NaN
    for (i in 0 until size) {
        val v = this[i]
        if (!v.isNaN() && (result.isNaN() || v > result)) result = v
    }
    return result
}

/** Most recent sample, or `NaN` when empty. */
public fun Float64Buffer.lastOrNaN(): Double = if (size == 0) Double.NaN else this[size - 1]
