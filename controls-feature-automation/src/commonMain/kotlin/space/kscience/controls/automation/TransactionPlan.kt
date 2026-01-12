package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.spec.RetryPolicy
import space.kscience.controls.api.spec.TimeoutPolicy
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Defines the strategy for handling failures within a [ParallelActionSpec].
 */
@Serializable
public enum class ParallelFailureStrategy {
    /** If any action fails, immediately cancel all other running actions and fail the block. */
    FAIL_FAST,

    /** Wait for all actions to complete, regardless of individual failures, then fail if any action failed. */
    COLLECT_ALL,

    /** Complete successfully even if some actions fail, as long as at least one succeeds (best-effort). */
    BEST_EFFORT,
}

/**
 * Defines the policy for handling a failure that occurs within a compensating action.
 */
@Serializable
public enum class CompensationPolicy {
    /** Abort the entire rollback process. The system may be left in an inconsistent state. Requires manual intervention. */
    ABORT,

    /** Continue the rollback process with the remaining compensating actions, but flag the transaction as requiring attention. */
    CONTINUE_AND_FLAG,

    /** Retry the failed compensating action according to its own retry policy, if defined. */
    RETRY,
}

/**
 * Defines the execution order for compensating actions within a [ParallelActionSpec].
 * This is crucial for correctly rolling back parallel operations that may have dependencies.
 */
@Serializable
public enum class CompensationOrder {
    /**
     * Execute compensating actions in the reverse order of their corresponding forward actions, one by one.
     * This is the safest default for most scenarios, as it unwinds the operation stack.
     */
    SEQUENTIAL_REVERSE,

    /**
     * Execute all compensating actions in parallel.
     * Use with caution, as it may lead to race conditions if compensations depend on each other.
     */
    PARALLEL
}


/**
 * A serializable, type-safe representation of a single action or a composite block of actions within a transaction plan.
 * The runtime ([space.kscience.controls.composite.old.services.TransactionCoordinator]) is responsible for interpreting and executing these specifications.
 *
 * @property key An optional unique key to make this action idempotent. The runtime should track executed keys
 *                          within the scope of a transaction to prevent duplicate operations on retry or replay.
 *                          The key's uniqueness guarantee is the responsibility of the plan creator.
 * @property compensation An optional compensating plan (Saga pattern) to be executed on rollback if this action
 *                        succeeded but a later action in the sequence failed.
 * @property compensationPolicy The policy for handling failures that occur *within* the compensating plan itself.
 * @property timeout An optional policy for timing out this specific action. If the action exceeds the timeout, it is
 *                   considered failed and will trigger a rollback.
 * @property retry An optional policy for retrying this action if it fails.
 */
@Serializable
public sealed interface ActionSpec : MetaRepr {
    public val key: String?
    public val compensation: TransactionPlan?
    public val compensationPolicy: CompensationPolicy
    public val timeout: TimeoutPolicy?
    public val retry: RetryPolicy?
}

@Serializable
@SerialName("parallel")
public data class ParallelActionSpec(
    val actions: List<ActionSpec>,
    val failureStrategy: ParallelFailureStrategy = ParallelFailureStrategy.FAIL_FAST,
    val compensationOrder: CompensationOrder = CompensationOrder.SEQUENTIAL_REVERSE,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("attach")
public data class AttachActionSpec(
    val deviceAddress: Address,
    val blueprintId: BlueprintId,
    val config: Meta = Meta.EMPTY,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("detach")
public data class DetachActionSpec(
    val deviceAddress: Address,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("start")
public data class StartActionSpec(
    val deviceAddress: Address,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("stop")
public data class StopActionSpec(
    val deviceAddress: Address,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("write")
public data class WritePropertyActionSpec(
    val deviceAddress: Address,
    val propertyName: Name,
    val value: Meta,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

@Serializable
@SerialName("sequence")
public data class SequenceActionSpec(
    val actions: List<ActionSpec>,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * An action that introduces a delay in the execution of a plan.
 * This action does not have a meaningful compensation, timeout, or retry policy.
 */
@Serializable
@SerialName("delay")
public data class DelayActionSpec(
    val duration: Duration,
) : ActionSpec {
    @Transient
    override val key: String? = null

    @Transient
    override val compensation: TransactionPlan? = null

    @Transient
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT

    @Transient
    override val timeout: TimeoutPolicy? = null

    @Transient
    override val retry: RetryPolicy? = null

    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * An action that pauses plan execution until a specific boolean property (a predicate)
 * on a device becomes `true`.
 *
 * @property deviceAddress The network-wide address of the device to monitor.
 * @property predicateName The name of the boolean property (must be of kind `PREDICATE`).
 * @property awaitTimeout An optional maximum duration to wait for the predicate to become true.
 *                      If the timeout is exceeded, the action fails, triggering a rollback.
 */
@Serializable
@SerialName("awaitPredicate")
public data class AwaitPredicateActionSpec(
    val deviceAddress: Address,
    val predicateName: Name,
    val awaitTimeout: Duration? = null,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * An action that pauses plan execution until an external signal is received from an operator or another system.
 *
 * @property signalId A unique identifier for the expected signal. The runtime uses this to correlate the waiting plan
 *                    with an incoming signal.
 * @property description An optional human-readable message to be displayed to an operator, explaining what is expected.
 * @property signalTimeout An optional maximum duration to wait for the signal. If no signal is received within this
 *                time, the action fails, and the plan is rolled back.
 */
@Serializable
@SerialName("awaitSignal")
public data class AwaitSignalActionSpec(
    val signalId: String,
    val description: String? = null,
    val signalTimeout: Duration? = null,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * A serializable representation of a predicate for a conditional action.
 * @property address The address of the device on which to evaluate the predicate.
 * @property propertyName The name of the boolean property to check.
 * @property expectedValue The value the property is expected to have for the condition to be true.
 */
@Serializable
public data class PredicateSpec(val address: Address, val propertyName: Name, val expectedValue: Boolean = true)

/**
 * A conditional action that executes one of two branches (`thenPlan` or `elsePlan`) based on the evaluation
 * of a predicate.
 *
 * @property predicate The [PredicateSpec] describing the condition to evaluate.
 * @property thenPlan The [TransactionPlan] to execute if the predicate is true.
 * @property elsePlan An optional [TransactionPlan] to execute if the predicate is false.
 */
@Serializable
@SerialName("condition")
public data class ConditionalActionSpec(
    val predicate: PredicateSpec,
    val thenPlan: TransactionPlan,
    val elsePlan: TransactionPlan? = null,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * An action that invokes another action on a specified device.
 * This is the primary mechanism for orchestrating device behaviors within a plan.
 *
 * @property deviceAddress The network-wide address of the target device.
 * @property actionName The name of the action to invoke on the target device.
 * @property input An optional [Meta] object representing the input arguments for the action.
 * @property outputKey An optional key to store the result of this action in the plan's execution context.
 *                     If provided, the result can be referenced by subsequent actions.
 */
@Serializable
@SerialName("invoke")
public data class InvokeActionSpec(
    val deviceAddress: Address,
    val actionName: Name,
    val input: Meta? = null,
    val outputKey: Name? = null,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}


/**
 * An action that iterates over a collection of items (previously stored in the plan's execution context)
 * and executes a sub-plan for each item.
 *
 * @property iterableName The name under which the collection of items is stored in the `PlanExecutionContext`.
 * @property loopVariableName The name of the variable that will hold the current item within the sub-plan's scope.
 * @property body The [TransactionPlan] to be executed for each item in the collection.
 */
@Serializable
@SerialName("loop")
public data class LoopActionSpec(
    val iterableName: Name,
    val loopVariableName: String,
    val body: TransactionPlan,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}

/**
 * An action that executes a `dataforge-data` task.
 *
 * @property taskBlueprintId The unique ID of the `TaskBlueprint` to be executed.
 * @property input The [Meta] input for the task.
 * @property outputKey The key under which the task's result (`DataTree<*>`) will be stored in the `PlanExecutionContext`.
 */
@Serializable
@SerialName("runTask")
public data class RunWorkspaceTaskSpec(
    val taskBlueprintId: String,
    val input: Meta,
    val outputKey: Name,
    override val key: String? = null,
    override val compensation: TransactionPlan? = null,
    override val compensationPolicy: CompensationPolicy = CompensationPolicy.ABORT,
    override val timeout: TimeoutPolicy? = null,
    override val retry: RetryPolicy? = null,
) : ActionSpec {
    override fun toMeta(): Meta = serializableToMeta(ActionSpec.serializer(), this)
}


/**
 * A container for a sequence of actions to be executed transactionally.
 * The plan consists of a single root [ActionSpec], which can be a composite action (sequence or parallel).
 *
 * @property rootAction The root action of the plan. If the plan contains multiple top-level steps,
 *                      they should be wrapped in a [SequenceActionSpec] or [ParallelActionSpec].
 * @property deadline An optional absolute time by which the entire transaction must complete. If the deadline is
 *                    exceeded, the transaction is considered failed, and a rollback is initiated. This provides
 *                    a global timeout for the whole operation.
 */
@Serializable
public data class TransactionPlan(
    val rootAction: ActionSpec,
    val deadline: Instant? = null,
) : MetaRepr {
    override fun toMeta(): Meta = rootAction.toMeta()
}