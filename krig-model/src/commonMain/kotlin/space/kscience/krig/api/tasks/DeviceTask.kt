package space.kscience.krig.api.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmInline

/** Stable id of a long-running device task created by an action. */
@Serializable
@JvmInline
public value class DeviceTaskId(public val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceTaskId must not be blank" }
    }
}

/** Portable phase of a long-running task exposed as device state and messages. */
@Serializable
public enum class DeviceTaskPhase {
    @SerialName("accepted")
    Accepted,

    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("cancelled")
    Cancelled,

    @SerialName("failed")
    Failed,
}

/** Human and machine-readable task progress snapshot. */
@Serializable
public data class DeviceTaskProgress(
    public val fraction: Double? = null,
    public val step: Name? = null,
    public val message: String? = null,
) {
    init {
        require(fraction == null || (!fraction.isNaN() && fraction in 0.0..1.0)) {
            "DeviceTaskProgress.fraction must be null or in [0.0, 1.0], got $fraction"
        }
    }
}

/** Serializable failure snapshot for task state; detailed faults may still be emitted as FaultMessage. */
@Serializable
public data class DeviceTaskFailure(
    public val faultType: Name,
    public val message: String? = null,
    public val details: Meta = Meta.EMPTY,
)

/** Observable state of a long-running task. */
@Serializable
public data class DeviceTaskState(
    public val taskId: DeviceTaskId,
    public val actionName: Name,
    public val phase: DeviceTaskPhase,
    public val progress: DeviceTaskProgress = DeviceTaskProgress(),
    public val result: Meta? = null,
    public val failure: DeviceTaskFailure? = null,
) {
    public val terminal: Boolean
        get() = phase == DeviceTaskPhase.Succeeded ||
                phase == DeviceTaskPhase.Cancelled ||
                phase == DeviceTaskPhase.Failed
}
