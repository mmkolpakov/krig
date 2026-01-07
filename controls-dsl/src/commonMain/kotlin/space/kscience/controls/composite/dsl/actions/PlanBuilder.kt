package space.kscience.controls.composite.dsl.actions

import space.kscience.controls.automation.ActionSpec
import space.kscience.controls.automation.AttachActionSpec
import space.kscience.controls.automation.AwaitPredicateActionSpec
import space.kscience.controls.automation.AwaitSignalActionSpec
import space.kscience.controls.automation.ConditionalActionSpec
import space.kscience.controls.automation.DelayActionSpec
import space.kscience.controls.automation.DetachActionSpec
import space.kscience.controls.automation.InvokeActionSpec
import space.kscience.controls.automation.ParallelActionSpec
import space.kscience.controls.automation.ParallelFailureStrategy
import space.kscience.controls.automation.PredicateSpec
import space.kscience.controls.automation.RunWorkspaceTaskSpec
import space.kscience.controls.automation.SequenceActionSpec
import space.kscience.controls.automation.StartActionSpec
import space.kscience.controls.automation.StopActionSpec
import space.kscience.controls.automation.TransactionPlan
import space.kscience.controls.automation.WritePropertyActionSpec
import space.kscience.controls.composite.dsl.children.AttachmentConfiguration
import space.kscience.controls.composite.dsl.children.deviceAttachment
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.spec.RestartStrategy
import space.kscience.controls.api.spec.RetryPolicy
import space.kscience.controls.api.spec.TimeoutPolicy
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.core.legacy_alpha_2.meta.DeviceActionSpec
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.meta.MutableDevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.MetaRef
import space.kscience.dataforge.meta.MutableMeta
import space.kscience.dataforge.meta.isEmpty
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.isEmpty
import space.kscience.dataforge.names.parseAsName
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A DSL marker for building device action plans.
 */
@DslMarker
public annotation class PlanDsl

/**
 * Converts an [AttachmentConfiguration] to its [Meta] representation.
 * This is an internal helper function.
 */
private fun AttachmentConfiguration.toMeta(): Meta = Meta {
    lifecycle?.let { config ->
        "lifecycle" put {
            "lifecycleMode" put config.lifecycleMode.name
            config.startTimeout?.let { "startTimeout" put it.inWholeMilliseconds }
            config.stopTimeout?.let { "stopTimeout" put it.inWholeMilliseconds }
            "onError" put config.onError.name
            "lazyAttach" put config.lazyAttach
            "restartPolicy" put {
                "maxAttempts" put config.restartPolicy.maxAttempts
                "resetOnSuccess" put config.restartPolicy.resetOnSuccess
                "strategy" put {
                    when (val strategy = config.restartPolicy.strategy) {
                        is RestartStrategy.Linear -> {
                            "type" put "linear"
                            "baseDelay" put strategy.baseDelay.toDouble(DurationUnit.MILLISECONDS)
                        }

                        is RestartStrategy.ExponentialBackoff -> {
                            "type" put "exponential"
                            "baseDelay" put strategy.baseDelay.toDouble(DurationUnit.MILLISECONDS)
                        }

                        is RestartStrategy.Fibonacci -> {
                            "type" put "fibonacci"
                            "baseDelay" put strategy.baseDelay.toDouble(DurationUnit.MILLISECONDS)
                        }
                    }
                }
            }
            "persistence" put {
                "enabled" put config.persistence.enabled
                "mode" put config.persistence.mode.name
                "interval" put config.persistence.interval.toDouble(DurationUnit.MILLISECONDS)
                "restoreOnStart" put config.persistence.restoreOnStart
            }
        }
    }
    meta?.let { "meta" put it }
    "startMode" put startMode.name
    if (childrenOverrides.isNotEmpty()) {
        "children" put {
            childrenOverrides.forEach { (name, config) ->
                set(name, config.toMeta())
            }
        }
    }
}

/**
 * A builder for a conditional block within a plan.
 */
@PlanDsl
public class ConditionalPlanBuilder {
    internal val branches = mutableListOf<Pair<PredicateSpec, TransactionPlan>>()
    internal var elsePlan: TransactionPlan? = null

    /**
     * Defines the primary `then` block for an `if` condition.
     */
    public fun then(block: PlanBuilder.() -> Unit) {
        // The first branch is always the 'then' part.
        if (branches.isNotEmpty()) {
            branches[0] = branches[0].copy(second = plan(block))
        }
    }

    /**
     * Defines an `else if` block.
     */
    public fun `else if`(
        predicate: DevicePropertySpec<*, Boolean>,
        deviceAddress: Address,
        expectedValue: Boolean = true,
        block: PlanBuilder.() -> Unit,
    ) {
        val predicateSpec = PredicateSpec(deviceAddress, predicate.name, expectedValue)
        branches.add(predicateSpec to plan(block))
    }

    /**
     * Defines the final `else` block.
     */
    public fun `else`(block: PlanBuilder.() -> Unit) {
        elsePlan = plan(block)
    }
}

/**
 * A builder for creating a `Meta`-representation of a transaction plan.
 */
@PlanDsl
public class PlanBuilder {
    private val actions = mutableListOf<ActionSpec>()

    private fun buildCompensation(block: (PlanBuilder.() -> Unit)?): TransactionPlan? {
        return if (block == null) {
            null
        } else {
            plan(block)
        }
    }

    /**
     * Adds a conditional execution block to the plan.
     *
     * @param predicate The boolean property to check. Must be of `PropertyKind.PREDICATE`.
     * @param deviceAddress The address of the device on which to evaluate the predicate.
     * @param expectedValue The expected value of the predicate for the `then` branch to execute.
     * @param block The DSL block to define the `then`, `else if`, and `else` branches.
     */
    public fun condition(
        predicate: DevicePropertySpec<*, Boolean>,
        deviceAddress: Address,
        expectedValue: Boolean = true,
        block: ConditionalPlanBuilder.() -> Unit,
    ) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        require(predicate.descriptor.kind == PropertyKind.PREDICATE) {
            "Property '${predicate.name}' used in a condition must be of kind PREDICATE."
        }
        val predicateSpec = PredicateSpec(deviceAddress, predicate.name, expectedValue)
        val builder = ConditionalPlanBuilder().apply {
            branches.add(predicateSpec to TransactionPlan(SequenceActionSpec(emptyList())))
            block()
        }

        var root: ConditionalActionSpec? = null
        for (i in builder.branches.indices.reversed()) {
            val (pred, plan) = builder.branches[i]
            val elsePlan = root?.let { TransactionPlan(it) } ?: builder.elsePlan
            root = ConditionalActionSpec(pred, plan, elsePlan)
        }
        root?.let { actions.add(it) }
    }

    /**
     * Adds a 'runTask' action to the plan, allowing it to execute a `dataforge-data` task
     * and store its result in the plan's execution context for later steps.
     *
     * @param taskBlueprintId The unique ID of the `TaskBlueprint` to be executed.
     * @param outputKey The key under which the task's `DataTree<*>` result will be stored in the `PlanExecutionContext`.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional compensating plan.
     * @param inputBuilder A DSL block to build the input [Meta] for the task.
     */
    public fun runTask(
        taskBlueprintId: String,
        outputKey: Name,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        inputBuilder: MutableMeta.() -> Unit
    ) {
        actions.add(
            RunWorkspaceTaskSpec(
                taskBlueprintId = taskBlueprintId,
                input = Meta(inputBuilder),
                outputKey = outputKey,
                key = key,
                compensation = buildCompensation(compensation)
            )
        )
    }

    /**
     * Adds an 'attach' action to the plan.
     * @param address The address of device to attach.
     * @param blueprint The blueprint defining the device's structure and logic.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional block defining a compensating plan (e.g., `detach`).
     * @param block A DSL block to configure the attachment.
     */
    public fun attach(
        address: Address,
        blueprint: DeviceBlueprint<*>,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        block: AttachmentConfiguration.() -> Unit = {},
    ) {
        val config = deviceAttachment(block).toMeta() // Convert DSL config to Meta
        actions.add(
            AttachActionSpec(
                address,
                blueprint.id,
                config,
                key,
                buildCompensation(compensation)
            )
        )
    }

    /**
     * Adds a 'detach' action to the plan.
     * @param address The address of the device to detach.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional block defining a compensating plan (e.g., `attach`).
     */
    public fun detach(
        address: Address,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
    ) {
        actions.add(DetachActionSpec(address, key, buildCompensation(compensation)))
    }

    /**
     * Creates a block of actions that will be executed sequentially.
     *
     * @param key An optional key for the entire sequence.
     * @param compensation An optional compensating plan for the sequence.
     * @param block The DSL block containing the sequential actions.
     */
    public fun sequence(
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        block: PlanBuilder.() -> Unit,
    ) {
        val nestedBuilder = PlanBuilder().apply(block)
        val spec = SequenceActionSpec(
            actions = nestedBuilder.actions,
            key = key,
            compensation = buildCompensation(compensation)
        )
        actions.add(spec)
    }

    /**
     * Creates a block of actions that will be executed in parallel.
     * The runtime will wait for all actions in this block to complete before proceeding.
     */
    public fun parallel(
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        block: PlanBuilder.() -> Unit,
    ) {
        val nestedBuilder = PlanBuilder().apply(block)
        val spec = ParallelActionSpec(
            actions = nestedBuilder.actions,
            key = key,
            compensation = buildCompensation(compensation)
        )
        actions.add(spec)
    }

    /**
     * Adds a 'start' action to the plan.
     * @param address The address of the device to start.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional block defining a compensating plan (e.g., `stop`).
     */
    public fun start(
        address: Address,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
    ) {
        actions.add(StartActionSpec(address, key, buildCompensation(compensation)))
    }

    /**
     * Adds a 'stop' action to the plan.
     * @param address The address of the device to stop.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional block defining a compensating plan (e.g., `start`).
     */
    public fun stop(
        address: Address,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
    ) {
        actions.add(StopActionSpec(address, key, buildCompensation(compensation)))
    }

    /**
     * Adds a 'write property' action to the plan.
     * @param T The type of the property value.
     * @param property The [MutableDevicePropertySpec] identifying the target property.
     * @param deviceAddress The address of the device on which to write the property.
     * @param value The value to write.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional block defining a compensating action (e.g., writing the old value back).
     */
    public fun <T> write(
        property: MutableDevicePropertySpec<*, T>,
        deviceAddress: Address,
        value: T,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
    ) {
        val metaValue = property.converter.convert(value)
        actions.add(
            WritePropertyActionSpec(
                deviceAddress,
                property.name,
                metaValue,
                key,
                buildCompensation(compensation)
            )
        )
    }

    /**
     * Creates a block of actions that will be executed in parallel for a collection of child devices ("a swarm").
     * This function generates a single [ParallelActionSpec] that contains the sub-plans for each child.
     * The `block` lambda is executed for each child, receiving the child's fully resolved network address,
     * allowing actions within the block to target that specific child.
     *
     * This is the primary DSL construct for orchestrating operations across multiple similar devices.
     *
     * @param childNames A collection of the local names of the child devices to operate on.
     * @param parentAddress The network address of the parent device that contains these children.
     * @param failureStrategy The strategy for handling failures within the parallel block. Defaults to `FAIL_FAST`.
     * @param key An optional key for the entire parallel operation.
     * @param compensation An optional compensating plan for the entire parallel block.
     * @param block A DSL block that defines the actions to be performed on each child. It receives the child's `Address`.
     */
    public fun forEach(
        childNames: Collection<Name>,
        parentAddress: Address,
        failureStrategy: ParallelFailureStrategy = ParallelFailureStrategy.FAIL_FAST,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        block: PlanBuilder.(childAddress: Address) -> Unit,
    ) {
        // Create a list of ActionSpecs, one for each child in the swarm.
        val parallelActions = childNames.map { childName ->
            // Construct the full, unique address for the current child.
            val childAddress = parentAddress.resolveChild(childName)

            // Create a temporary builder for the sub-plan of this specific child.
            val childPlanBuilder = PlanBuilder()

            // Apply the user's logic to this child's builder, passing in its specific address.
            childPlanBuilder.block(childAddress)

            // Build the sub-plan and extract its root action.
            childPlanBuilder.build().rootAction
        }

        // Create the single ParallelActionSpec that wraps all individual child actions.
        val parallelSpec = ParallelActionSpec(
            actions = parallelActions,
            failureStrategy = failureStrategy,
            key = key,
            compensation = buildCompensation(compensation)
        )

        // Add the composite parallel action to the main plan.
        actions.add(parallelSpec)
    }

    /**
     * A convenience overload for `forEach` that accepts child names as strings.
     * This overload is given a different name on the JVM (`forEachString`) to avoid
     * a signature clash with the `Collection<Name>` overload due to type erasure.
     *
     * @see forEach
     */
    @JvmName("forEachString")
    public fun forEach(
        childNameStrings: Collection<String>,
        parentAddress: Address,
        failureStrategy: ParallelFailureStrategy = ParallelFailureStrategy.FAIL_FAST,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        block: PlanBuilder.(childAddress: Address) -> Unit,
    ) {
        forEach(
            childNames = childNameStrings.map { it.parseAsName() },
            parentAddress = parentAddress,
            failureStrategy = failureStrategy,
            key = key,
            compensation = compensation,
            block = block
        )
    }

    /**
     * Adds a 'delay' action to the plan, pausing execution for the specified duration.
     *
     * @param duration The duration of the delay.
     */
    public fun delay(duration: Duration) {
        actions.add(DelayActionSpec(duration))
    }

    /**
     * Adds an 'await' action, pausing plan execution until a predicate property on a device becomes `true`.
     *
     * @param predicate The [DevicePropertySpec] for a boolean property. The delegate must be of kind `PREDICATE`.
     * @param deviceAddress The network address of the device to monitor.
     * @param timeout An optional maximum duration to wait. If the predicate does not become true within this time,
     *                the action fails, triggering a rollback of the plan.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional compensating plan.
     * @param retry An optional policy for retrying the await operation if it fails (e.g., due to a timeout).
     */
    public fun await(
        predicate: DevicePropertySpec<*, Boolean>,
        deviceAddress: Address,
        timeout: Duration? = null,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        retry: RetryPolicy? = null,
    ) {
        require(predicate.descriptor.kind == PropertyKind.PREDICATE) {
            "Property '${predicate.name}' used in 'await' must be of kind PREDICATE, but is '${predicate.descriptor.kind}'."
        }
        actions.add(
            AwaitPredicateActionSpec(
                deviceAddress = deviceAddress,
                predicateName = predicate.name,
                awaitTimeout = timeout,
                key = key,
                compensation = buildCompensation(compensation),
                retry = retry
            )
        )
    }

    /**
     * Adds an 'awaitSignal' action, pausing plan execution until an external signal is received from an operator
     * or another system. This is crucial for building interactive, human-in-the-loop workflows.
     *
     * @param signalId A unique identifier for the expected signal. A UI or external script would use this ID to
     *                 send a continuation signal to the runtime.
     * @param description An optional human-readable message describing what is expected from the operator.
     *                    This can be displayed in a user interface.
     * @param timeout An optional maximum duration to wait for the signal. If no signal is received within this
     *                time, the action fails, and the plan is rolled back.
     * @param key An optional key for idempotency and referencing.
     * @param compensation An optional compensating plan.
     */
    public fun awaitSignal(
        signalId: String,
        description: String? = null,
        timeout: Duration? = null,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
    ) {
        actions.add(
            AwaitSignalActionSpec(
                signalId = signalId,
                description = description,
                signalTimeout = timeout,
                key = key,
                compensation = buildCompensation(compensation)
            )
        )
    }

    /**
     * Adds an 'invoke' action, executing another action on a specified device.
     * This is the primary way to orchestrate device behaviors in a plan.
     *
     * @param I The input type of the action being invoked.
     * @param O The output type of the action being invoked.
     * @param action The [DeviceActionSpec] of the action to invoke.
     * @param deviceAddress The network address of the target device.
     * @param outputKey An optional key to store the result of this action in the plan's execution context.
     * @param key An optional key for idempotency and for referencing this step's result.
     * @param compensation An optional compensating plan.
     * @param timeout An optional policy for timing out this action.
     * @param retry An optional policy for retrying this action if it fails.
     * @param inputBuilder A DSL block to construct the input [Meta] for the action. References to previous steps can be used here.
     */
    public fun <I, O> invoke(
        action: DeviceActionSpec<*, I, O>,
        deviceAddress: Address,
        outputKey: Name? = null,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        timeout: TimeoutPolicy? = null,
        retry: RetryPolicy? = null,
        inputBuilder: MutableMeta.() -> Unit = {}
    ) {
        val inputMeta = Meta(inputBuilder)
        actions.add(
            InvokeActionSpec(
                deviceAddress = deviceAddress,
                actionName = action.name,
                input = if (inputMeta.isEmpty()) null else inputMeta,
                outputKey = outputKey,
                key = key,
                compensation = buildCompensation(compensation),
                timeout = timeout,
                retry = retry
            )
        )
    }

    /**
     * A convenience overload for `invoke` for actions that take `Unit` as input.
     */
    public fun <O> invoke(
        action: DeviceActionSpec<*, Unit, O>,
        deviceAddress: Address,
        outputKey: Name? = null,
        key: String? = null,
        compensation: (PlanBuilder.() -> Unit)? = null,
        timeout: TimeoutPolicy? = null,
        retry: RetryPolicy? = null,
    ) {
        invoke(action, deviceAddress, outputKey, key, compensation, timeout, retry) {}
    }


    /**
     * Builds the final `TransactionPlan` object representing the plan.
     * This method defines the strategy for constructing the root action of the plan.
     *
     * - If the builder is empty (0 actions), it creates an empty `SequenceActionSpec`, representing a no-op plan.
     * - If the builder contains exactly one action, that action becomes the root of the plan.
     * - If the builder contains multiple actions, they are implicitly wrapped in a `SequenceActionSpec`
     *   to ensure they are executed in the order they were defined.
     */
    public fun build(): TransactionPlan {
        val rootAction = when (actions.size) {
            0 -> SequenceActionSpec(emptyList()) // An empty plan is a valid no-op.
            1 -> actions.first()
            else -> SequenceActionSpec(actions.toList()) // Multiple actions are treated as a sequence.
        }
        return TransactionPlan(rootAction)
    }
}

/**
 * Creates a formatted reference string to the entire result object of a previous step.
 * The runtime will replace this with the full [Meta] result of the step.
 *
 * @param stepKey The unique key of the target step, defined in its `invoke(key = "...")` call.
 * @return A formatted reference template string, e.g., `"${my-step-key}"`.
 */
@PlanDsl
public fun ref(stepKey: String): String = $$"${$$stepKey}"

/**
 * Creates a formatted, type-safe reference string pointing to a specific field within a previous step's result.
 * This function uses a [MetaRef] from an [ActionOutputSpec] to ensure type safety and correctness at compile time.
 *
 * @param T The type of the referenced value.
 * @param stepKey The unique key of the target step.
 * @param output The [MetaRef] pointing to the desired field in the action's output.
 * @return A formatted reference template string, e.g., `"${my-step-key::details.temperature}"`.
 */
@OptIn(DFExperimental::class)
@PlanDsl
public fun <T> ref(stepKey: String, output: MetaRef<T>): String {
    val path = if (output.name.isEmpty()) "" else "::${output.name}"
    return $$"${$$stepKey$$path}"
}


/**
 * Creates a type-safe [TransactionPlan] using a DSL.
 */
public fun plan(block: PlanBuilder.() -> Unit): TransactionPlan =
    PlanBuilder().apply(block).build()

/**
 * Creates a Meta-based representation of a transaction plan using a type-safe DSL.
 * This is useful for serialization and transport.
 */
public fun planAsMeta(block: PlanBuilder.() -> Unit): Meta =
    MetaConverter.meta.convert(plan(block).toMeta())