package space.kscience.krig.core.expressions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.combineAll
import space.kscience.krig.api.expressions.And
import space.kscience.krig.api.expressions.BooleanConstant
import space.kscience.krig.api.expressions.Comparison
import space.kscience.krig.api.expressions.ComparisonOperation
import space.kscience.krig.api.expressions.Condition
import space.kscience.krig.api.expressions.Not
import space.kscience.krig.api.expressions.Or
import space.kscience.krig.core.state.DeviceState
import space.kscience.krig.core.state.combine
import space.kscience.krig.core.state.map
import kotlin.time.Instant

/**
 * Compiles a [Condition] tree into a reactive [DeviceState<Boolean>].
 *
 * Reuses [NumericExpression.compile][compile] for operand expressions and the same quality propagation:
 * a missing/`NaN` operand surfaces as a `null` Boolean (unknown) rather than a silent `false`, and quality
 * is worst-wins across all referenced bindings. Boolean combinators apply three-valued (Kleene) logic so an
 * unknown sub-condition cannot fabricate a definite alarm state.
 */
public suspend fun Condition.compile(ctx: ExpressionContext): DeviceState<Boolean> =
    when (this) {
        is BooleanConstant -> booleanConstantState(value)
        is Comparison -> {
            val l = left.compile(ctx)
            val r = right.compile(ctx)
            l.combine(r) { lv, rv ->
                if (lv == null || rv == null || lv.isNaN() || rv.isNaN()) null else operation.test(lv, rv)
            }
        }
        is Not -> operand.compile(ctx).map { it?.not() }
        is And -> combineConditions(operands.map { it.compile(ctx) }, BooleanReduction.AND)
        is Or -> combineConditions(operands.map { it.compile(ctx) }, BooleanReduction.OR)
    }

private fun ComparisonOperation.test(left: Double, right: Double): Boolean = when (this) {
    ComparisonOperation.LESS -> left < right
    ComparisonOperation.LESS_OR_EQUAL -> left <= right
    ComparisonOperation.GREATER -> left > right
    ComparisonOperation.GREATER_OR_EQUAL -> left >= right
    ComparisonOperation.EQUAL -> left == right
    ComparisonOperation.NOT_EQUAL -> left != right
}

private enum class BooleanReduction { AND, OR }

/**
 * Three-valued (Kleene) reduction of an empty list yields the operator's identity (`AND` → `true`,
 * `OR` → `false`); otherwise a dominating value short-circuits the result (`false` for `AND`, `true` for
 * `OR`) regardless of unknowns, and a remaining unknown leaves the result `null`.
 */
private fun BooleanReduction.reduce(values: List<Boolean?>): Boolean? {
    val dominant = this == BooleanReduction.OR
    if (values.any { it == dominant }) return dominant
    if (values.any { it == null }) return null
    return !dominant
}

private fun combineConditions(
    states: List<DeviceState<Boolean>>,
    reduction: BooleanReduction,
): DeviceState<Boolean> {
    if (states.isEmpty()) return booleanConstantState(reduction == BooleanReduction.AND)
    return object : DeviceState<Boolean> {
        override val stateValue: ObservedValue<Boolean?>
            get() {
                val samples = states.map { it.stateValue }
                return ObservedValue(
                    value = reduction.reduce(samples.map { it.value }),
                    time = samples.maxOf { it.time },
                    quality = samples.map { it.quality }.combineAll(),
                )
            }
        override val stateFlow: Flow<ObservedValue<Boolean?>> =
            combine(states.map { it.stateFlow }) { samples ->
                ObservedValue(
                    value = reduction.reduce(samples.map { it.value }),
                    time = samples.maxOf { it.time },
                    quality = samples.map { it.quality }.combineAll(),
                )
            }
    }
}

private fun booleanConstantState(value: Boolean): DeviceState<Boolean> =
    object : DeviceState<Boolean> {
        override val stateValue = ObservedValue<Boolean?>(value, Instant.DISTANT_PAST, DataQuality.GOOD)
        override val stateFlow: Flow<ObservedValue<Boolean?>> = flowOf(stateValue)
    }
