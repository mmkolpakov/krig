package space.kscience.controls.composite.dsl.actions

import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import space.kscience.controls.composite.dsl.ActionDelegateProvider
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.composite.dsl.properties.ActionDescriptorBuilder
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.automation.PlanExecutorDevice
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.automation.PlanExecutorFeature
import space.kscience.controls.automation.TaskExecutorFeature
import space.kscience.controls.automation.TransactionPlan
import space.kscience.controls.core.serialization.serializable
import space.kscience.controls.core.descriptors.ActionDescriptor
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.unit
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.MetaConverter.Companion.meta
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A centralized, internal factory for creating action delegate providers.
 * This function encapsulates the boilerplate for creating and registering a [space.kscience.controls.core.meta.DeviceActionSpec].
 * It handles both inline logic (`execute` lambda) and external logic (`logicId`).
 */
@PublishedApi
internal fun <D : Device, I, O> createActionDelegateProvider(
    specProvider: DeviceSpecification<D>.() -> Unit,
    name: Name?,
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit,
    execute: (suspend D.(input: I) -> O?)?,
    logicId: Name? = null,
    logicVersionConstraint: String? = null
): ActionDelegateProvider<D, I, O> {
    return PropertyDelegateProvider { thisRef, property ->
        thisRef.apply(specProvider)
        val actionName = name ?: property.name.parseAsName()
        val dslBuilder = ActionDescriptorBuilder(actionName).apply {
            this.logicId = logicId
            this.logicVersionConstraint = logicVersionConstraint
            descriptorBuilder()
        }
        val descriptor = dslBuilder.build()

        val devAction = object : DeviceActionSpec<D, I, O> {
            override val name: Name = actionName
            override val descriptor: ActionDescriptor = descriptor
            override val inputConverter: MetaConverter<I> = inputConverter
            override val outputConverter: MetaConverter<O> = outputConverter
            override suspend fun execute(device: D, input: I): O? = when {
                execute != null -> withContext(device.coroutineContext) { device.execute(input) }
                logicId != null -> error(
                    "Action '$actionName' is backed by external logic '$logicId' and cannot be executed directly. " +
                            "The runtime must resolve it via an ActionLogicProvider."
                )
                else -> error("Neither inline logic nor external logicId is provided for action '$actionName'.")
            }
        }
        thisRef.registerActionSpec(devAction)
        ReadOnlyProperty { _, _ -> devAction }
    }
}


/**
 * A base delegate for creating a device action specification with an inline implementation.
 * The visibility of the action is determined by the scope (`public`, `private`, etc.) in which this delegate is used.
 * The resulting [DeviceActionSpec] is **automatically registered** in the blueprint being constructed.
 *
 * This function should be used within a [space.kscience.controls.composite.dsl.DeviceSpecification] to define an action.
 *
 * @param D The type of the device contract.
 * @param I The input type of the action.
 * @param O The output type of the action.
 * @param inputConverter A [MetaConverter] to handle serialization of the input type.
 * @param outputConverter A [MetaConverter] to handle serialization of the output type.
 * @param descriptorBuilder A DSL block to configure the action's static [ActionDescriptor].
 * @param name An optional explicit name for the action. If not provided, the delegated property name is used.
 * @param execute A suspendable lambda that defines the action's runtime logic.
 * @return A [PropertyDelegateProvider] for a read-only property that holds the created [DeviceActionSpec].
 */
public fun <D : Device, I, O> DeviceSpecification<D>.action(
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    execute: suspend D.(input: I) -> O?,
): ActionDelegateProvider<D, I, O> = createActionDelegateProvider(
    specProvider = {},
    name = name,
    inputConverter = inputConverter,
    outputConverter = outputConverter,
    descriptorBuilder = descriptorBuilder,
    execute = execute
)


/**
 * A base delegate for creating a device action specification that references an external logic component.
 * This overload does not take an `execute` lambda. Instead, it relies on the runtime to resolve the
 * `logicId` to a [space.kscience.controls.core.contracts.DeviceActionLogic] implementation.
 *
 * @param logicId The unique [Name] of the external action logic to be resolved by the runtime.
 * @param logicVersionConstraint An optional version constraint for the external logic.
 * @see action for other parameters.
 */
public fun <D : Device, I, O> DeviceSpecification<D>.action(
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    logicId: Name,
    logicVersionConstraint: String? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
): ActionDelegateProvider<D, I, O> = createActionDelegateProvider(
    specProvider = {},
    name = name,
    inputConverter = inputConverter,
    outputConverter = outputConverter,
    descriptorBuilder = descriptorBuilder,
    execute = null,
    logicId = logicId,
    logicVersionConstraint = logicVersionConstraint
)


/**
 * Declares a device action that takes no input ([Unit]) and produces no output ([Unit]),
 * with an inline implementation. This is the most common type of action for simple commands.
 *
 * @see action
 */
public fun <D : Device> DeviceSpecification<D>.unitAction(
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    execute: suspend D.() -> Unit,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, Unit, Unit>>> = action(
    MetaConverter.unit, MetaConverter.unit, descriptorBuilder, name
) { execute() }

/**
 * Declares a device action that takes no input and produces no output, implemented by external logic.
 *
 * @see action
 */
public fun <D : Device> DeviceSpecification<D>.unitAction(
    logicId: Name,
    logicVersionConstraint: String? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, Unit, Unit>>> = action(
    MetaConverter.unit, MetaConverter.unit, logicId, logicVersionConstraint, descriptorBuilder, name
)

/**
 * Declares a device action that takes a [Meta] object as input and returns a [Meta] object as output,
 * with an inline implementation.
 *
 * @see action
 */
public fun <D : Device> DeviceSpecification<D>.metaAction(
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    execute: suspend D.(Meta) -> Meta?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, Meta, Meta>>> = action(
    meta, meta, descriptorBuilder, name, execute
)

/**
 * Declares a device action that takes a [Meta] object as input and returns a [Meta] object,
 * implemented by external logic.
 *
 * @see action
 */
public fun <D : Device> DeviceSpecification<D>.metaAction(
    logicId: Name,
    logicVersionConstraint: String? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, Meta, Meta>>> = action(
    meta, meta, logicId, logicVersionConstraint, descriptorBuilder, name
)

/**
 * A base delegate for creating a task-based device action, accepting explicit serializers.
 *
 * This version is not inline and requires manually providing the serializers for input and output types.
 * It is the most flexible way to define a task action, especially when dealing with complex generic types
 * (like `List<T>` or `Map<K,V>`) for which the compiler cannot infer a serializer automatically.
 *
 * @param D The device contract, which **must** implement [TaskExecutorDevice].
 * @param I The input type for the task.
 * @param O The output type for the task.
 * @param taskBlueprintId The unique identifier of the `TaskBlueprint` to be executed.
 * @param inputSerializer The explicit [KSerializer] for the input type `I`.
 * @param outputSerializer The explicit [KSerializer] for the output type `O`.
 * @param distributable If `true`, the task is marked as a candidate for execution on a remote compute grid.
 * @param descriptorBuilder A DSL block for further configuring the action's descriptor.
 * @param name An optional explicit name for the action.
 * @return A [PropertyDelegateProvider] for the created [DeviceActionSpec].
 */
@OptIn(DFExperimental::class)
public inline fun <reified I, O, D> DeviceSpecification<D>.taskAction(
    taskBlueprintId: String,
    inputSerializer: KSerializer<I>,
    outputSerializer: KSerializer<O>,
    distributable: Boolean = false,
    crossinline descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
): ActionDelegateProvider<D, I, O> where D : Device, D : TaskExecutorDevice {
    val executeLogic: suspend D.(I) -> O = {
        error(
            "Action '${name ?: "anonymous"}' on device '${this.name}' is backed by a DataForge Task. " +
                    "It must be executed by a task-aware runtime via the 'TaskExecutorDevice.executeTask' contract, not directly."
        )
    }

    return createActionDelegateProvider(
        specProvider = { registerFeature(TaskExecutorFeature(listOf(taskBlueprintId))) },
        name = name,
        inputConverter = MetaConverter.serializable(inputSerializer),
        outputConverter = MetaConverter.serializable(outputSerializer),
        descriptorBuilder = {
            this.taskBlueprintId = taskBlueprintId
            this.distributable = distributable
            this.taskInputTypeName = inputSerializer.descriptor.serialName
            this.taskOutputTypeName = outputSerializer.descriptor.serialName
            descriptorBuilder()
        },
        execute = executeLogic
    )
}

/**
 * Declares a device action that is implemented by a `dataforge-data` `Task`,
 * inferring serializers for simple, non-generic types.
 *
 * This is a convenience wrapper around the more explicit `taskAction` that accepts serializers.
 * It uses `reified` type parameters to automatically find serializers for `I` and `O`.
 * This is the recommended approach for tasks with simple, `@Serializable` input/output classes.
 *
 * For complex generic types, use the overload that accepts explicit `KSerializer` instances.
 *
 * @see taskAction
 */
@OptIn(DFExperimental::class)
public inline fun <reified I, reified O, D> DeviceSpecification<D>.taskAction(
    taskBlueprintId: String,
    distributable: Boolean = false,
    noinline descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
): ActionDelegateProvider<D, I, O> where D : Device, D : TaskExecutorDevice =
    taskAction(
        taskBlueprintId = taskBlueprintId,
        inputSerializer = serializer<I>(),
        outputSerializer = serializer<O>(),
        distributable = distributable,
        descriptorBuilder = descriptorBuilder,
        name = name
    )


/**
 * Declares a device action implemented by a [TransactionPlan].
 * This function is constrained to device contracts `D` that implement [PlanExecutorDevice], ensuring
 * compile-time safety. Using this function automatically adds [PlanExecutorFeature] to the blueprint.
 *
 * The plan is serialized into the action's descriptor metadata. The runtime is responsible for
 * finding a [space.kscience.controls.automation.TransactionCoordinator] and executing the plan,
 * which should ultimately invoke the [PlanExecutorDevice.executePlan] method on the device.
 * The local `execute` block throws an error to prevent direct execution by a non-plan-aware runtime.
 *
 * @param D The device contract, which **must** implement [PlanExecutorDevice].
 * @param name An optional explicit name for the plan-based action.
 * @param descriptorBuilder A DSL block for configuring the action's descriptor.
 * @param block A DSL block defining the [TransactionPlan].
 */
public fun <D> DeviceSpecification<D>.plan(
    name: Name? = null,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    block: PlanBuilder.() -> Unit,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, Unit, Unit>>> where D : Device, D : PlanExecutorDevice {
    val plan = plan(block)
    val planMeta = plan.toMeta()

    return createActionDelegateProvider(
        specProvider = { registerFeature(PlanExecutorFeature()) },
        name = name,
        inputConverter = MetaConverter.unit,
        outputConverter = MetaConverter.unit,
        descriptorBuilder = {
            meta { "plan" put planMeta }
            descriptorBuilder()
        },
        execute = {
            error(
                "Action '${name ?: "anonymous"}' on device '${this.name}' is backed by a TransactionPlan. " +
                        "It must be executed by a plan-aware runtime via the 'PlanExecutorDevice.executePlan' contract, not directly."
            )
        }
    )
}