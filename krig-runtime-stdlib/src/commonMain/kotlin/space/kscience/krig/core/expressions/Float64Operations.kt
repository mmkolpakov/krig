package space.kscience.krig.core.expressions

import space.kscience.kmath.operations.Float64Field
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

/**
 * Operation dispatch for [space.kscience.krig.api.expressions.NumericExpression] evaluation.
 *
 * Transcendental/analytic operations (`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`exp`/`ln`/`sqrt`, binary
 * `pow`) are **delegated to KMath [Float64Field]** via its named-operation dispatch.
 *
 * The krig-local tables below cover exactly what KMath's field algebra does **not** name:
 * - element-wise helpers absent from `Float64Field` (`abs`, `log10`, rounding, `sign`, `min`/`max`,
 *   `hypot`, `atan2`);
 * - arithmetic with krig's deliberate guard semantics — `div` yields `NaN` (not `±∞`) on a zero
 *   divisor, so it stays local;
 * - reductions (`sum`/`prod`/`min`/`max`/`mean`), which are not binary algebra operations.
 *
 * Unknown names fall through to `Float64Field`, which throws for an undefined operation.
 */
internal object Float64Operations {

    private val localUnary: Map<String, (Double) -> Double> = mapOf(
        "neg" to { -it },
        "abs" to ::abs,
        "log10" to ::log10,
        "round" to ::round,
        "floor" to ::floor,
        "ceil" to ::ceil,
        "sign" to ::sign,
    )

    private val localBinary: Map<String, (Double, Double) -> Double> = mapOf(
        "add" to { a, b -> a + b },
        "sub" to { a, b -> a - b },
        "mul" to { a, b -> a * b },
        "div" to { a, b -> if (b == 0.0) Double.NaN else a / b },
        "min" to ::min,
        "max" to ::max,
        "hypot" to ::hypot,
        "atan2" to ::atan2,
    )

    private val naryOps: Map<String, (List<Double>) -> Double> = mapOf(
        "sum" to { it.sum() },
        "prod" to { it.fold(1.0) { acc, v -> acc * v } },
        "min" to { it.minOrNull() ?: Double.NaN },
        "max" to { it.maxOrNull() ?: Double.NaN },
        "mean" to { it.average() },
    )

    fun unary(name: String): (Double) -> Double =
        localUnary[name] ?: Float64Field.unaryOperationFunction(name)

    fun binary(name: String): (Double, Double) -> Double =
        localBinary[name] ?: Float64Field.binaryOperationFunction(name)

    fun nary(name: String): (List<Double>) -> Double =
        naryOps[name] ?: error("Unknown n-ary op '$name'")
}
