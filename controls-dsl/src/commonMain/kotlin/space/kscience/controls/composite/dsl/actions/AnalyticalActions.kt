package space.kscience.controls.composite.dsl.actions

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import space.kscience.controls.analytics.ActionFrequencyTaskSpec
import space.kscience.controls.analytics.AveragePropertyValueTaskSpec
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.composite.dsl.properties.ActionDescriptorBuilder
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Instant

/**
 * Declares a device action that calculates the average value of a numeric property over a given time range.
 *
 * This function is a specialized wrapper around [taskAction]. It is constrained to devices that
 * implement [TaskExecutorDevice] and automatically configures the action to use the standard
 * `controls.task.analytics.averageProperty` task blueprint provided by the runtime.
 *
 * The input to the resulting action is an [space.kscience.controls.analytics.AveragePropertyValueTaskSpec], and the output is a [Double].
 *
 * @param name An optional explicit name for the analytical action. If not provided, the delegated property name is used.
 * @param descriptorBuilder A DSL block for further configuring the action's descriptor (e.g., adding descriptions or permissions).
 */
@OptIn(DFExperimental::class)
public fun <D> DeviceSpecification<D>.averagePropertyValueAction(
    name: Name? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, AveragePropertyValueTaskSpec, Double>>>
        where D : Device, D : TaskExecutorDevice {
    return taskAction<AveragePropertyValueTaskSpec, Double, D>(
        taskBlueprintId = AveragePropertyValueTaskSpec.BLUEPRINT_ID.value,
        descriptorBuilder = descriptorBuilder,
        name = name
    )
}

/**
 * Declares a device action that calculates the invocation frequency of another action over a time range,
 * aggregated into time windows.
 *
 * This function is a specialized wrapper around [taskAction] for devices that implement [TaskExecutorDevice].
 * It configures the action to use the standard `controls.task.analytics.actionFrequency` task blueprint.
 *
 * The input to the resulting action is an [space.kscience.controls.analytics.ActionFrequencyTaskSpec], and the output is a `Map<Instant, Long>`,
 * where each key is the beginning of a time window and the value is the number of invocations in that window.
 *
 * @param name An optional explicit name for the analytical action.
 * @param descriptorBuilder A DSL block for further configuring the action's descriptor.
 */
@OptIn(DFExperimental::class)
public fun <D> DeviceSpecification<D>.actionFrequencyAction(
    name: Name? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, ActionFrequencyTaskSpec, Map<Instant, Long>>>>
        where D : Device, D : TaskExecutorDevice {
    return taskAction(
        taskBlueprintId = ActionFrequencyTaskSpec.BLUEPRINT_ID.value,
        inputSerializer = ActionFrequencyTaskSpec.serializer(),
        outputSerializer = MapSerializer(Instant.serializer(), Long.serializer()),
        descriptorBuilder = descriptorBuilder,
        name = name
    )
}