package space.kscience.krig.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.attributes.latencyBudget
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.meta.Meta

// --- Latency budget observers ------------------------------------------------------

/**
 * Reports a `latency-budget` warning when the observed call exceeded the descriptor's
 * declared [latencyBudget][space.kscience.krig.api.descriptors.attributes.BehaviorAttribute.latencyBudget]
 * (or [defaultBudget] when the descriptor is silent).
 *
 * Non-aborting — for hard cap-and-fail use [ReadPipelineSpec.defaultTimeout].
 */
public class LatencyBudgetReadObserver(
    private val defaultBudget: Duration? = null,
    private val onViolation: (String) -> Unit = {},
) : ReadObserver {
    override suspend fun onRead(
        spec: DevicePropertySpec<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val budget = spec.descriptor.latencyBudget ?: defaultBudget ?: return
        val elapsed = durationNanos.nanoseconds
        if (violatesBudget(elapsed, budget)) {
            onViolation("latency budget exceeded on read '${spec.name}': elapsed=$elapsed, budget=$budget")
        }
    }
}

/** Write-plane analogue of [LatencyBudgetReadObserver]. */
public class LatencyBudgetWriteObserver(
    private val defaultBudget: Duration? = null,
    private val onViolation: (String) -> Unit = {},
) : WriteObserver {
    override suspend fun onWrite(
        spec: MutableDevicePropertySpec<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val budget = spec.descriptor.latencyBudget ?: defaultBudget ?: return
        val elapsed = durationNanos.nanoseconds
        if (violatesBudget(elapsed, budget)) {
            onViolation("latency budget exceeded on write '${spec.name}': elapsed=$elapsed, budget=$budget")
        }
    }
}

/** Action-plane analogue of [LatencyBudgetReadObserver]. */
public class LatencyBudgetActionObserver(
    private val defaultBudget: Duration? = null,
    private val onViolation: (String) -> Unit = {},
) : ActionObserver {
    override suspend fun onAction(
        spec: DeviceActionSpec<*, *, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val budget = spec.descriptor.latencyBudget ?: defaultBudget ?: return
        val elapsed = durationNanos.nanoseconds
        if (violatesBudget(elapsed, budget)) {
            onViolation("latency budget exceeded on action '${spec.name}': elapsed=$elapsed, budget=$budget")
        }
    }
}

private fun violatesBudget(elapsed: Duration, budget: Duration): Boolean =
    if (budget == Duration.ZERO) true else elapsed > budget

// --- Audit observers ---------------------------------------------------------------

/**
 * Records each read attempt to [AuditService] when the service is active. Captures
 * principal, device, property, and (on failure) the [DeviceFault.code]. Runs after the
 * call so both successful reads and rejected/failed reads can be audited.
 */
public class AuditReadObserver(
    private val deviceName: String,
    private val auditService: AuditService,
) : ReadObserver {
    override suspend fun onRead(
        spec: DevicePropertySpec<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        if (!auditService.isActive) return
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        auditService.record(
            principal,
            AuditAction.DeviceRead,
            Meta {
                "device" put deviceName
                "property" put spec.name.toString()
                if (fault != null) "fault" put fault.code
            },
        )
    }
}

/** Write-plane analogue of [AuditReadObserver]. */
public class AuditWriteObserver(
    private val deviceName: String,
    private val auditService: AuditService,
) : WriteObserver {
    override suspend fun onWrite(
        spec: MutableDevicePropertySpec<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        if (!auditService.isActive) return
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        auditService.record(
            principal,
            AuditAction.DeviceWrite,
            Meta {
                "device" put deviceName
                "property" put spec.name.toString()
                if (fault != null) "fault" put fault.code
            },
        )
    }
}

/** Action-plane analogue of [AuditReadObserver]. */
public class AuditActionObserver(
    private val deviceName: String,
    private val auditService: AuditService,
) : ActionObserver {
    override suspend fun onAction(
        spec: DeviceActionSpec<*, *, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        if (!auditService.isActive) return
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        auditService.record(
            principal,
            AuditAction.DeviceExecute,
            Meta {
                "device" put deviceName
                "action" put spec.name.toString()
                if (fault != null) "fault" put fault.code
            },
        )
    }
}
