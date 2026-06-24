package space.kscience.krig.api.expressions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable Boolean predicate tree for device alarms / threshold logic.
 */
@Serializable
public sealed interface Condition

/** Comparison operators over two numeric operands. */
@Serializable
public enum class ComparisonOperation {
    LESS,
    LESS_OR_EQUAL,
    GREATER,
    GREATER_OR_EQUAL,
    EQUAL,
    NOT_EQUAL,
}

/**
 * Numeric comparison `left <op> right`. `EQUAL`/`NOT_EQUAL` use exact `Double` equality — for tolerant
 * comparisons author an explicit `abs(left - right) lt epsilon` instead.
 */
@Serializable
@SerialName("cond.comparison")
public data class Comparison(
    public val operation: ComparisonOperation,
    public val left: NumericExpression,
    public val right: NumericExpression,
) : Condition

/** Logical conjunction. Vacuously `true` for an empty [operands] list. */
@Serializable
@SerialName("cond.and")
public data class And(
    public val operands: List<Condition>,
) : Condition

/** Logical disjunction. Vacuously `false` for an empty [operands] list. */
@Serializable
@SerialName("cond.or")
public data class Or(
    public val operands: List<Condition>,
) : Condition

/** Logical negation. A `null` (unknown) operand stays `null`. */
@Serializable
@SerialName("cond.not")
public data class Not(
    public val operand: Condition,
) : Condition

/** Boolean literal. */
@Serializable
@SerialName("cond.constant")
public data class BooleanConstant(
    public val value: Boolean,
) : Condition
