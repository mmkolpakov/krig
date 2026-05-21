package space.kscience.krig.core.expressions

import kotlin.math.*

public object OpRegistry {

    private val unaryOps: MutableMap<String, (Double) -> Double> = mutableMapOf(
        "neg" to { -it }, "abs" to { abs(it) }, "sqrt" to { sqrt(it) },
        "sin" to { sin(it) }, "cos" to { cos(it) }, "tan" to { tan(it) },
        "asin" to { asin(it) }, "acos" to { acos(it) }, "atan" to { atan(it) },
        "exp" to { exp(it) }, "ln" to { ln(it) }, "log10" to { log10(it) },
        "round" to { round(it) }, "floor" to { floor(it) }, "ceil" to { ceil(it) },
        "sign" to { sign(it) },
    )

    private val binaryOps: MutableMap<String, (Double, Double) -> Double> = mutableMapOf(
        "add" to { a, b -> a + b }, "sub" to { a, b -> a - b },
        "mul" to { a, b -> a * b }, "div" to { a, b -> if (b == 0.0) Double.NaN else a / b },
        "pow" to { a, b -> a.pow(b) },
        "min" to { a, b -> min(a, b) }, "max" to { a, b -> max(a, b) },
        "hypot" to { a, b -> hypot(a, b) }, "atan2" to { a, b -> atan2(a, b) },
    )

    private val naryOps: MutableMap<String, (List<Double>) -> Double> = mutableMapOf(
        "sum" to { it.sum() }, "prod" to { it.fold(1.0) { a, v -> a * v } },
        "min" to { it.minOrNull() ?: Double.NaN },
        "max" to { it.maxOrNull() ?: Double.NaN },
        "mean" to { it.average() },
    )

    public fun registerUnary(name: String, op: (Double) -> Double) { unaryOps[name] = op }
    public fun registerBinary(name: String, op: (Double, Double) -> Double) { binaryOps[name] = op }
    public fun registerNAry(name: String, op: (List<Double>) -> Double) { naryOps[name] = op }

    internal fun unary(name: String): (Double) -> Double = unaryOps[name] ?: error("Unknown unary op '$name'")
    internal fun binary(name: String): (Double, Double) -> Double = binaryOps[name] ?: error("Unknown binary op '$name'")
    internal fun nary(name: String): (List<Double>) -> Double = naryOps[name] ?: error("Unknown n-ary op '$name'")
}
