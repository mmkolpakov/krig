package space.kscience.krig.core.expressions

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.attributes.virtualProperty
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.expressions.NumericExpression
import space.kscience.krig.api.expressions.bindings
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.state.DeviceState

/**
 * Runtime view of a descriptor-backed virtual numeric property.
 */
public data class VirtualPropertyPlan(
    public val descriptor: PropertyDescriptor,
    public val expression: NumericExpression,
    public val bindings: Set<Binding> = expression.bindings(),
)

public fun PropertyDescriptor.virtualPropertyPlan(): VirtualPropertyPlan? {
    val attribute = virtualProperty ?: return null
    return VirtualPropertyPlan(this, attribute.expression)
}

public suspend fun VirtualPropertyPlan.compile(ctx: ExpressionContext): DeviceState<Double> =
    expression.compile(ctx)

public suspend fun PropertyDescriptor.compileVirtualProperty(ctx: ExpressionContext): DeviceState<Double>? =
    virtualPropertyPlan()?.compile(ctx)

public suspend fun Device.virtualPropertyState(
    property: Name,
    ctx: ExpressionContext,
): DeviceState<Double>? =
    propertyDescriptors[property]?.compileVirtualProperty(ctx)
