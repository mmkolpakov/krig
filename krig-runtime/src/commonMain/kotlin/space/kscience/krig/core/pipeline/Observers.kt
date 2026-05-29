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
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.attributes.latencyBudget
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.displayType
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.AuditService

/** Reports a warning when an operation exceeds its descriptor/default latency budget. */
public class LatencyBudgetObserver(
    private val defaultBudget: Duration? = null,
    private val onViolation: (String) -> Unit = {},
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        val budget = context.descriptor.latencyBudget ?: defaultBudget ?: return
        val elapsed = durationNanos.nanoseconds
        if (elapsed > budget || budget == Duration.ZERO) {
            onViolation("latency budget exceeded on ${context.kind} '${context.name}': elapsed=$elapsed, budget=$budget")
        }
    }
}

private const val DEFAULT_AUDIT_BUFFER_CAPACITY: Int = 1024

private data class AuditRecord(
    val principal: Principal,
    val action: AuditAction,
    val details: Meta,
)

/** Non-blocking audit writer for pipeline defaults. */
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
                        // Audit must never change operation semantics.
                    }
                }
            }
        }
    }

    public fun record(principal: Principal, action: AuditAction, details: Meta) {
        if (auditService.isActive) records.tryEmit(AuditRecord(principal, action, details))
    }
}

/** Direct audit observer. Prefer [BufferedAuditObserver] when audit storage can suspend. */
public class AuditObserver(
    private val hostName: String,
    private val auditService: AuditService,
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        if (!auditService.isActive) return
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        auditService.record(principal, context.auditAction() ?: return, context.auditDetails(hostName, fault))
    }
}

/** Non-blocking audit observer backed by [BufferedAuditSink]. */
public class BufferedAuditObserver(
    private val hostName: String,
    private val sink: BufferedAuditSink,
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        val principal = currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal
        sink.record(principal, context.auditAction() ?: return, context.auditDetails(hostName, fault))
    }
}

private fun OperationContext.auditAction(): AuditAction? =
    when (kind) {
        OperationKinds.Read -> AuditAction.DeviceRead
        OperationKinds.Write -> AuditAction.DeviceWrite
        OperationKinds.Action -> AuditAction.DeviceExecute
        else -> null
    }

private fun OperationContext.auditDetails(hostName: String, fault: OperationFault?): Meta =
    Meta {
        OperationFaultDetails.DEVICE put hostName
        val key = if (kind == OperationKinds.Action) OperationFaultDetails.ACTION else OperationFaultDetails.PROPERTY
        key put name.toString()
        if (fault != null) OperationFaultDetails.FAULT put fault.displayType
    }
