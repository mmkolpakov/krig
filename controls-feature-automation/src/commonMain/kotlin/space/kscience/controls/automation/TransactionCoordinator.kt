package space.kscience.controls.automation

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.faults.SerializableDeviceFailure
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * The result of a transaction execution.
 */
public sealed interface TransactionResult {
    /**
     * Indicates that the transaction completed successfully.
     */
    public data object Success : TransactionResult

    /**
     * Indicates that the transaction failed.
     * @property failure A structured, serializable representation of the error.
     */
    public data class Failure(val failure: SerializableDeviceFailure) : TransactionResult
}

/**
 * A contract for a service that executes a [TransactionPlan].
 * This service is a critical component of the runtime, responsible for orchestrating
 * a sequence of device operations, ensuring atomicity (all or nothing) through mechanisms
 * like compensating actions (Saga pattern).
 */
public interface TransactionCoordinator : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Executes a given [TransactionPlan] within a specific [ExecutionContext].
     * The implementation must handle the sequence of actions, manage idempotency keys,
     * and trigger compensating plans in case of failure.
     *
     * @param plan The transaction plan to execute.
     * @param context The execution context, providing security and tracing information.
     * @return A [TransactionResult] indicating the outcome of the transaction.
     */
    public suspend fun execute(plan: TransactionPlan, context: ExecutionContext): TransactionResult

    public companion object : PluginFactory<TransactionCoordinator> {
        override val tag: PluginTag = PluginTag("device.transaction.coordinator", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default factory throws an error, as a concrete implementation must be provided by a runtime.
         */
        override fun build(context: Context, meta: Meta): TransactionCoordinator {
            error("TransactionCoordinator is a service interface and requires a runtime-specific implementation.")
        }
    }
}