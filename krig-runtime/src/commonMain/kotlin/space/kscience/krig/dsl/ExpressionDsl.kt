package space.kscience.krig.dsl

import space.kscience.krig.api.expressions.*
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Operator-style DSL for building [NumericExpression] trees.
 *
 * ```kotlin
 * val formula: NumericExpression = expr {
 *     ref("motor", "rpm") * 2.0 + ref("sensor", "temp")
 * }
 * ```
 */

// ── Arithmetic operators ──

public operator fun NumericExpression.plus(other: NumericExpression): NumericExpression =
    Binary("add", this, other)

public operator fun NumericExpression.minus(other: NumericExpression): NumericExpression =
    Binary("sub", this, other)

public operator fun NumericExpression.times(other: NumericExpression): NumericExpression =
    Binary("mul", this, other)

public operator fun NumericExpression.div(other: NumericExpression): NumericExpression =
    Binary("div", this, other)

// Scalar overloads
public operator fun NumericExpression.plus(other: Double): NumericExpression = this + constant(other)
public operator fun NumericExpression.minus(other: Double): NumericExpression = this - constant(other)
public operator fun NumericExpression.times(other: Double): NumericExpression = this * constant(other)
public operator fun NumericExpression.div(other: Double): NumericExpression = this / constant(other)

public operator fun Double.plus(other: NumericExpression): NumericExpression = constant(this) + other
public operator fun Double.minus(other: NumericExpression): NumericExpression = constant(this) - other
public operator fun Double.times(other: NumericExpression): NumericExpression = constant(this) * other
public operator fun Double.div(other: NumericExpression): NumericExpression = constant(this) / other

// Unary negation
public operator fun NumericExpression.unaryMinus(): NumericExpression = Unary("neg", this)

// ── Factories ──

public fun ref(deviceName: String, propertyName: String): Binding =
    Binding(deviceName.asName(), propertyName.asName())

public fun ref(deviceName: Name, propertyName: Name): Binding =
    Binding(deviceName, propertyName)

public fun constant(value: Double): Constant = Constant(value)

public inline fun expr(body: () -> NumericExpression): NumericExpression = body()

// ── Math functions ──

public fun sin(arg: NumericExpression): NumericExpression = Unary("sin", arg)
public fun cos(arg: NumericExpression): NumericExpression = Unary("cos", arg)
public fun tan(arg: NumericExpression): NumericExpression = Unary("tan", arg)
public fun sqrt(arg: NumericExpression): NumericExpression = Unary("sqrt", arg)
public fun abs(arg: NumericExpression): NumericExpression = Unary("abs", arg)
public fun exp(arg: NumericExpression): NumericExpression = Unary("exp", arg)
public fun ln(arg: NumericExpression): NumericExpression = Unary("ln", arg)
public fun log10(arg: NumericExpression): NumericExpression = Unary("log10", arg)

public fun pow(base: NumericExpression, exponent: NumericExpression): NumericExpression =
    Binary("pow", base, exponent)

public fun sumOf(vararg args: NumericExpression): NumericExpression =
    NAry("sum", args.toList())
public fun productOf(vararg args: NumericExpression): NumericExpression =
    NAry("prod", args.toList())
public fun meanOf(vararg args: NumericExpression): NumericExpression =
    NAry("mean", args.toList())

// Threshold/alarm conditions (boolean [Condition] tree) live in `ConditionDsl.kt`.
