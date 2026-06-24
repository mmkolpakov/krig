package space.kscience.krig.api.expressions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * Serializable, monomorphic computation tree for deriving a numeric (`Double`) device value.
 *
 * Leaf nodes ([Binding] references a device property, [Constant] holds a literal); inner nodes apply
 * a string-named operation over child expressions. Every node evaluates to `Double` — the tree is
 * deliberately monomorphic, mirroring KMath `MST` (the generic belongs to the interpreting `Algebra<T>`,
 * not to the tree). Boolean alarm/threshold logic is a separate concern and is **not** modelled here.
 *
 * Compile into a reactive pipeline with [space.kscience.krig.core.expressions.compile].
 *
 * Operator DSL in [space.kscience.krig.dsl] builds the same tree with Kotlin syntax:
 * `ref("m","rpm") * 2.0 + ref("s","temp")`.
 *
 * **KMath seam.** Operations are string-named, so the analytic subset maps onto a KMath `MST` /
 * `Float64Field` without a hard dependency in this module: [Constant] → `MST.Numeric`, [Binding] →
 * `MST.Symbol`, [Unary]/[Binary] → `MST.Unary`/`MST.Binary`. The evaluator delegates transcendental
 * dispatch (`sin`/`cos`/…/`exp`/`ln`/`sqrt`/`pow`) to KMath `Float64Field`; arithmetic and reductions
 * (`sum`/`mean`/…) that KMath's field algebra does not name remain krig-local. A consumer that wants
 * string parsing or JVM-bytecode compilation adds `kmath-ast` on its side — krig stays neutral.
 */
@Serializable
public sealed interface NumericExpression

// ── Leaf nodes ──

/** References a device property. Resolves to a [Double] value at evaluation time. */
@Serializable
@SerialName("expr.binding")
public data class Binding(
    public val deviceName: Name,
    public val propertyName: Name,
) : NumericExpression

/** Scalar literal. */
@Serializable
@SerialName("expr.constant")
public data class Constant(
    public val value: Double,
) : NumericExpression

// ── Operation nodes (string-named, Double-valued) ──

/** Unary: `sin(ref)`, `abs(ref)`, `-ref`. */
@Serializable
@SerialName("expr.unary")
public data class Unary(
    public val operation: String,
    public val argument: NumericExpression,
) : NumericExpression

/** Binary: `a + b`, `a * b`, `pow(a, b)`. */
@Serializable
@SerialName("expr.binary")
public data class Binary(
    public val operation: String,
    public val left: NumericExpression,
    public val right: NumericExpression,
) : NumericExpression

/** Variadic: `sumOf(a, b, c)`, `meanOf(x, y, z)`. */
@Serializable
@SerialName("expr.nary")
public data class NAry(
    public val operation: String,
    public val operands: List<NumericExpression>,
) : NumericExpression
