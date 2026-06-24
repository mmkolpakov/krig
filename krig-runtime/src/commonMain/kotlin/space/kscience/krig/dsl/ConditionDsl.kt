package space.kscience.krig.dsl

import space.kscience.krig.api.expressions.*

/**
 * Boolean-condition DSL for threshold and alarm logic, building a [Condition] tree (compiled to
 * `DeviceState<Boolean>`). Kept separate from the numeric [NumericExpression] DSL in `ExpressionDsl.kt`:
 * `ref("s", "temp") gt 100.0 and (ref("s", "p") lt 2.0)`.
 */

// ── Comparisons ──

public infix fun NumericExpression.lt(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.LESS, this, other)
public infix fun NumericExpression.le(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.LESS_OR_EQUAL, this, other)
public infix fun NumericExpression.gt(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.GREATER, this, other)
public infix fun NumericExpression.ge(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.GREATER_OR_EQUAL, this, other)
public infix fun NumericExpression.eq(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.EQUAL, this, other)
public infix fun NumericExpression.neq(other: NumericExpression): Condition =
    Comparison(ComparisonOperation.NOT_EQUAL, this, other)

public infix fun NumericExpression.lt(other: Double): Condition = this lt constant(other)
public infix fun NumericExpression.le(other: Double): Condition = this le constant(other)
public infix fun NumericExpression.gt(other: Double): Condition = this gt constant(other)
public infix fun NumericExpression.ge(other: Double): Condition = this ge constant(other)
public infix fun NumericExpression.eq(other: Double): Condition = this eq constant(other)
public infix fun NumericExpression.neq(other: Double): Condition = this neq constant(other)

// ── Boolean combinators ──

/** Conjunction. Nested [And] operands are flattened so the tree stays shallow. */
public infix fun Condition.and(other: Condition): Condition =
    And(flatten<And>(this) { it.operands } + flatten<And>(other) { it.operands })

/** Disjunction. Nested [Or] operands are flattened so the tree stays shallow. */
public infix fun Condition.or(other: Condition): Condition =
    Or(flatten<Or>(this) { it.operands } + flatten<Or>(other) { it.operands })

public operator fun Condition.not(): Condition = if (this is Not) operand else Not(this)

public fun condition(value: Boolean): Condition = BooleanConstant(value)

private inline fun <reified C : Condition> flatten(
    condition: Condition,
    operands: (C) -> List<Condition>,
): List<Condition> = if (condition is C) operands(condition) else listOf(condition)
