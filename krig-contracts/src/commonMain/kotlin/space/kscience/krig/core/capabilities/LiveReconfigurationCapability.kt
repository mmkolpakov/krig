package space.kscience.krig.core.capabilities

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.result.OperationOutcome

/**
 * Opt-in live reconfiguration surface for devices that can safely apply runtime config
 * changes.
 */
public interface LiveReconfigurationCapability : Capability<LiveReconfigurationState> {
    override val key: CapabilityKey<*> get() = Key

    public suspend fun validate(update: Meta): OperationOutcome<ReconfigurationPlan>

    public suspend fun apply(plan: ReconfigurationPlan): OperationOutcome<Meta>

    public suspend fun rollback(plan: ReconfigurationPlan, cause: Throwable?): OperationOutcome<Unit> =
        OperationOutcome.Ok(Unit)

    public suspend fun reconfigure(update: Meta): OperationOutcome<Meta> {
        val plan = when (val validation = validate(update)) {
            is OperationOutcome.Fail -> return validation
            is OperationOutcome.Ok -> validation.value
        }
        return try {
            when (val applied = apply(plan)) {
                is OperationOutcome.Ok -> {
                    state.record(applied.value)
                    applied
                }
                is OperationOutcome.Fail -> {
                    when (val rollback = rollback(plan, null)) {
                        is OperationOutcome.Fail -> rollback
                        is OperationOutcome.Ok -> applied
                    }
                }
            }
        } catch (cause: Throwable) {
            when (rollback(plan, cause)) {
                is OperationOutcome.Fail -> Unit
                is OperationOutcome.Ok -> Unit
            }
            throw cause
        }
    }

    public companion object Key : CapabilityKey<LiveReconfigurationCapability> {
        override val id: Name = "krig.capability.liveReconfiguration".parseAsName()
    }
}

public data class ReconfigurationPlan(
    public val update: Meta,
    public val previous: Meta = Meta.EMPTY,
)

public class LiveReconfigurationState(initial: Meta = Meta.EMPTY) {
    private val mutableCurrent: MutableStateFlow<Meta> = MutableStateFlow(initial)

    public val current: StateFlow<Meta> = mutableCurrent.asStateFlow()

    internal fun record(meta: Meta) {
        mutableCurrent.value = meta
    }
}

public fun staticReconfigurationRejected(message: String): OperationOutcome.Fail =
    OperationOutcome.Fail(GenericOperationFault(message = message))
