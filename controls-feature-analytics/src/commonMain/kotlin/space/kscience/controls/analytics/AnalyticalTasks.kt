package space.kscience.controls.analytics

import kotlinx.serialization.Serializable
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.identifiers.toBlueprintId
import space.kscience.dataforge.names.Name
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A serializable specification for a task that calculates the average value of a numeric property
 * over a specified time range.
 *
 * The runtime is expected to provide a `TaskBlueprint` for this specification that queries the
 * `AuditLogService` for historical `PropertyChangedMessage`s, extracts numeric values,
 * and computes their average.
 *
 * @property deviceAddress The network-wide address of the device whose property is being analyzed.
 * @property propertyName The name of the numeric property to analyze.
 * @property startTime The start of the time range for the analysis (inclusive).
 * @property endTime The end of the time range for the analysis (inclusive).
 */
@Serializable
public data class AveragePropertyValueTaskSpec(
    val deviceAddress: Address,
    val propertyName: Name,
    val startTime: Instant,
    val endTime: Instant,
) {
    public companion object {
        /**
         * The unique identifier for the blueprint that executes this task specification.
         */
        public val BLUEPRINT_ID: BlueprintId = "controls.task.analytics.averageProperty".toBlueprintId()
    }
}

/**
 * A serializable specification for a task that calculates the frequency of an action's invocations
 * over a specified time range, aggregated into time windows.
 *
 * The runtime is expected to provide a `TaskBlueprint` for this specification that queries the
 * `AuditLogService` for historical messages related to the action (e.g., `ActionDispatched` from
 * a `TelemetryService` if available, or inferred from property changes caused by the action)
 * and counts them in discrete time windows.
 *
 * @property deviceAddress The network-wide address of the device whose action is being analyzed.
 * @property actionName The name of the action to analyze.
 * @property startTime The start of the time range for the analysis (inclusive).
 * @property endTime The end of the time range for the analysis (inclusive).
 * @property window The duration of the time window for aggregating invocation counts. For example,
 *                  a window of 1 minute will produce a count of invocations for each minute
 *                  within the total time range.
 */
@Serializable
public data class ActionFrequencyTaskSpec(
    val deviceAddress: Address,
    val actionName: Name,
    val startTime: Instant,
    val endTime: Instant,
    val window: Duration,
) {
    public companion object {
        /**
         * The unique identifier for the blueprint that executes this task specification.
         */
        public val BLUEPRINT_ID: BlueprintId = "controls.task.analytics.actionFrequency".toBlueprintId()
    }
}