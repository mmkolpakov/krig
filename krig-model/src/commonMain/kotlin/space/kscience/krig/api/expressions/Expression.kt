package space.kscience.krig.api.expressions

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.attributes.SafeType
import space.kscience.attributes.safeTypeOf
import space.kscience.dataforge.names.Name

/**
 * Serializable computation tree for deriving device values.
 *
 * Leaf nodes ([Binding] references a device property, [Constant] holds a literal);
 * inner nodes apply operations over child expressions.
 *
 * Compile into a reactive pipeline with
 * [space.kscience.krig.core.expressions.compile].
 *
 * Operator DSL in [space.kscience.krig.dsl] builds the same tree with
 * Kotlin syntax: `ref("m","rpm") * 2.0 + ref("s","temp")`.
 *
 * **KMath `kmath-ast` seam.** Operations are string-named, so this tree maps node-for-node onto a
 * KMath `MST` without a dependency here: [Constant] → `MST.Numeric`, [Binding] → `MST.Symbol`,
 * [Unary]/[Binary] → `MST.Unary`/`MST.Binary` (same function name), [NAry] → a fold of `MST.Binary`.
 * A consumer that wants string parsing or JVM-bytecode compilation adds `kmath-ast` on its side and
 * bridges via `String.parseMath()` / `MST.compileToExpression(Float64Field)` — krig stays neutral.
 */
@Polymorphic
@Serializable
public sealed interface Expression<out T> {
    /** Runtime type descriptor for dispatch and validation. Computed, not serialised. */
    @Transient
    public val type: SafeType<@UnsafeVariance T>
}

// ── Leaf nodes ──

/** References a device property. Resolves to a [Double] value at evaluation time. */
@Serializable
@SerialName("expr.binding")
public data class Binding(
    public val deviceName: Name,
    public val propertyName: Name,
) : Expression<Double> {
    override val type: SafeType<Double> get() = doubleType
}

/** Scalar literal. [type] is computed from the value's class at construction. */
@Serializable
@SerialName("expr.constant")
public data class Constant<out T>(
    public val value: T,
) : Expression<T> {
    @Transient
    override val type: SafeType<@UnsafeVariance T> = expressionTypeOf(value)
}

// ── Operation nodes (all return [Double] for alpha-3) ──

/** Unary: `sin(ref)`, `abs(ref)`, `-ref`. */
@Serializable
@SerialName("expr.unary")
public data class Unary(
    public val operation: String,
    public val argument: Expression<Double>,
) : Expression<Double> {
    override val type: SafeType<Double> get() = doubleType
}

/** Binary: `a + b`, `a * b`, `pow(a, b)`. */
@Serializable
@SerialName("expr.binary")
public data class Binary(
    public val operation: String,
    public val left: Expression<Double>,
    public val right: Expression<Double>,
) : Expression<Double> {
    override val type: SafeType<Double> get() = doubleType
}

/** Variadic: `sumOf(a, b, c)`, `meanOf(x, y, z)`. */
@Serializable
@SerialName("expr.nary")
public data class NAry(
    public val operation: String,
    public val operands: List<Expression<Double>>,
) : Expression<Double> {
    override val type: SafeType<Double> get() = doubleType
}

// ── Helpers ──

@PublishedApi
internal fun <T> expressionTypeOf(value: T): SafeType<T> {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
        is Double -> doubleType as SafeType<T>
        is Int -> intType as SafeType<T>
        is Long -> safeTypeOf<Long>() as SafeType<T>
        is Boolean -> boolType as SafeType<T>
        is String -> safeTypeOf<String>() as SafeType<T>
        else -> safeTypeOf<Any>() as SafeType<T>
    }
}

internal val doubleType: SafeType<Double> = safeTypeOf()
internal val intType: SafeType<Int> = safeTypeOf()
internal val boolType: SafeType<Boolean> = safeTypeOf()
