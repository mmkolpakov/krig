package space.kscience.krig.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.attributes.latencyBudget
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
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
        spec: DevicePropertyContract<*>,
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
        spec: MutableDevicePropertyContract<*>,
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
        spec: DeviceActionContract<*, *>,
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

private const val DEFAULT_AUDIT_BUFFER_CAPACITY: Int = 1024

private data class AuditRecord(
    val principal: Principal,
    val action: AuditAction,
    val details: Meta,
)

/**
 * Buffered audit writer for pipeline defaults. Inline observers stay quick while
 * potentially slow audit storage is drained by [scope]. Overflow is lossy by design:
 * audit is observability, not the hardware control path.
 */
public class BufferedAuditSink(
    scope: CoroutineScope,
    private val auditService: AuditService,
    bufferCapacity: Int = DEFAULT_AUDIT_BUFFER_CAPACITY,
) {
    private val records = MutableSharedFlow<AuditRecord>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive, got $bufferCapacity" }
        if (auditService.isActive) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                records.collect { record ->
                    try {
                        auditService.record(record.principal, record.action, record.details)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Audit must never change device operation semantics.
                    }
                }
            }
        }
    }

    public fun record(principal: Principal, action: AuditAction, details: Meta) {
        if (auditService.isActive) records.tryEmit(AuditRecord(principal, action, details))
    }
}

/**
 * Records each read attempt to [AuditService] when the service is active. Captures
 * principal, device, property, and (on failure) the [DeviceFault.code]. Runs after the
 * call so both successful reads and rejected/failed reads can be audited.
 *
 * This observer writes inline. Prefer [BufferedAuditReadObserver] on default device
 * pipelines when the audit backend can suspend on I/O.
 */
public class AuditReadObserver(
    private val deviceName: String,
    private val auditService: AuditService,
) : ReadObserver {
    override suspend fun onRead(
        spec: DevicePropertyContract<*>,
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

/** Non-blocking read audit observer backed by [BufferedAuditSink]. */
public class BufferedAuditReadObserver(
    private val deviceName: String,
    private val sink: BufferedAuditSink,
) : ReadObserver {
    override suspend fun onRead(
        spec: DevicePropertyContract<*>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        sink.record(
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
        spec: MutableDevicePropertyContract<*>,
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

/** Non-blocking write audit observer backed by [BufferedAuditSink]. */
public class BufferedAuditWriteObserver(
    private val deviceName: String,
    private val sink: BufferedAuditSink,
) : WriteObserver {
    override suspend fun onWrite(
        spec: MutableDevicePropertyContract<*>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        sink.record(
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
        spec: DeviceActionContract<*, *>,
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

/** Non-blocking action audit observer backed by [BufferedAuditSink]. */
public class BufferedAuditActionObserver(
    private val deviceName: String,
    private val sink: BufferedAuditSink,
) : ActionObserver {
    override suspend fun onAction(
        spec: DeviceActionContract<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    ) {
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        sink.record(
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
