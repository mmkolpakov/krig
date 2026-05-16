package space.kscience.krig.dsl

import space.kscience.krig.api.expressions.*
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Operator-style DSL for building [Expression] trees.
 *
 * ```kotlin
 * val formula: Expression<Double> = expr {
 *     ref("motor", "rpm") * 2.0 + ref("sensor", "temp")
 * }
 * ```
 */

// ── Arithmetic operators ──

public operator fun Expression<Double>.plus(other: Expression<Double>): Expression<Double> =
    Binary("add", this, other)

public operator fun Expression<Double>.minus(other: Expression<Double>): Expression<Double> =
    Binary("sub", this, other)

public operator fun Expression<Double>.times(other: Expression<Double>): Expression<Double> =
    Binary("mul", this, other)

public operator fun Expression<Double>.div(other: Expression<Double>): Expression<Double> =
    Binary("div", this, other)

// Scalar overloads
public operator fun Expression<Double>.plus(other: Double): Expression<Double> = this + constant(other)
public operator fun Expression<Double>.minus(other: Double): Expression<Double> = this - constant(other)
public operator fun Expression<Double>.times(other: Double): Expression<Double> = this * constant(other)
public operator fun Expression<Double>.div(other: Double): Expression<Double> = this / constant(other)

public operator fun Double.plus(other: Expression<Double>): Expression<Double> = constant(this) + other
public operator fun Double.minus(other: Expression<Double>): Expression<Double> = constant(this) - other
public operator fun Double.times(other: Expression<Double>): Expression<Double> = constant(this) * other
public operator fun Double.div(other: Expression<Double>): Expression<Double> = constant(this) / other

// Unary negation
public operator fun Expression<Double>.unaryMinus(): Expression<Double> = Unary("neg", this)

// ── Factories ──

public fun ref(deviceName: String, propertyName: String): Binding =
    Binding(deviceName.asName(), propertyName.asName())

public fun ref(deviceName: Name, propertyName: Name): Binding =
    Binding(deviceName, propertyName)

public fun constant(value: Double): Constant<Double> = Constant(value)

public inline fun <T> expr(body: () -> Expression<T>): Expression<T> = body()

// ── Math functions ──

public fun sin(arg: Expression<Double>): Expression<Double> = Unary("sin", arg)
public fun cos(arg: Expression<Double>): Expression<Double> = Unary("cos", arg)
public fun tan(arg: Expression<Double>): Expression<Double> = Unary("tan", arg)
public fun sqrt(arg: Expression<Double>): Expression<Double> = Unary("sqrt", arg)
public fun abs(arg: Expression<Double>): Expression<Double> = Unary("abs", arg)
public fun exp(arg: Expression<Double>): Expression<Double> = Unary("exp", arg)
public fun ln(arg: Expression<Double>): Expression<Double> = Unary("ln", arg)
public fun log10(arg: Expression<Double>): Expression<Double> = Unary("log10", arg)

public fun pow(base: Expression<Double>, exponent: Expression<Double>): Expression<Double> =
    Binary("pow", base, exponent)

public fun sumOf(vararg args: Expression<Double>): Expression<Double> =
    NAry("sum", args.toList())
public fun productOf(vararg args: Expression<Double>): Expression<Double> =
    NAry("prod", args.toList())
public fun meanOf(vararg args: Expression<Double>): Expression<Double> =
    NAry("mean", args.toList())
