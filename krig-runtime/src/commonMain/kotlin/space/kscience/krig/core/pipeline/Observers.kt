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
import space.kscience.krig.api.context.ExecutionContext
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.attributes.latencyBudget
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.displayType
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuditDetailKeys
import space.kscience.krig.api.services.IdentityProvider
import space.kscience.krig.api.identifiers.isSpecified

/**
 * Reports a violation when an operation exceeds its descriptor/default latency budget.
 *
 * A zero or absent budget disables the check. [onViolation] must be supplied by the assembler —
 * a silent default would swallow every violation (the default assembly logs through the device
 * context logger).
 */
public class LatencyBudgetObserver(
    private val defaultBudget: Duration? = null,
    private val onViolation: (String) -> Unit,
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        val budget = context.descriptor.latencyBudget ?: defaultBudget ?: return
        if (budget <= Duration.ZERO) return
        val elapsed = durationNanos.nanoseconds
        if (elapsed > budget) {
            onViolation("latency budget exceeded on ${context.kind} '${context.name}': elapsed=$elapsed, budget=$budget")
        }
    }
}

private const val DEFAULT_AUDIT_BUFFER_CAPACITY: Int = 1024

private data class AuditRecord(
    val principal: Principal,
    val action: String,
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
                    } catch (_: Exception) {
                        // Audit must never change operation semantics; fatal errors still surface.
                    }
                }
            }
        }
    }

    public fun record(principal: Principal, action: String, details: Meta) {
        if (auditService.isActive) records.tryEmit(AuditRecord(principal, action, details))
    }
}

/** Direct audit observer. Prefer [BufferedAuditObserver] when audit storage can suspend. */
public class AuditObserver(
    private val hostName: String,
    private val auditService: AuditService,
    private val identityProvider: IdentityProvider,
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        if (!auditService.isActive) return
        val principal = currentPipelinePrincipal(identityProvider)
        val executionContext = currentCoroutineContext().executionContext
        auditService.record(
            principal,
            context.auditAction() ?: return,
            context.auditDetails(hostName, fault, principal, executionContext),
        )
    }
}

/** Non-blocking audit observer backed by [BufferedAuditSink]. */
public class BufferedAuditObserver(
    private val hostName: String,
    private val sink: BufferedAuditSink,
    private val identityProvider: IdentityProvider,
) : OperationObserver {
    override suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    ) {
        val principal = currentPipelinePrincipal(identityProvider)
        val executionContext = currentCoroutineContext().executionContext
        sink.record(
            principal,
            context.auditAction() ?: return,
            context.auditDetails(hostName, fault, principal, executionContext),
        )
    }
}

private fun OperationContext.auditAction(): String? =
    when (kind) {
        OperationKinds.Read, OperationKinds.Write, OperationKinds.Action -> "device.${kind.name}"
        else -> null
    }

private fun OperationContext.auditDetails(
    hostName: String,
    fault: OperationFault?,
    principal: Principal,
    executionContext: ExecutionContext?,
): Meta =
    Meta {
        OperationFaultDetails.DEVICE put hostName
        val key = if (kind == OperationKinds.Action) OperationFaultDetails.ACTION else OperationFaultDetails.PROPERTY
        key put name.toString()
        AuditDetailKeys.EXECUTING_PRINCIPAL put principal.name
        executionContext?.callerIdentity?.takeIf { it.isNotBlank() }?.let { identity ->
            AuditDetailKeys.CALLER_IDENTITY put identity
        }
        executionContext?.onBehalfOf?.let { delegatedPrincipal ->
            AuditDetailKeys.ON_BEHALF_OF put delegatedPrincipal.name
        }
        executionContext?.correlationId?.takeIf { it.isSpecified }?.let { correlationId ->
            AuditDetailKeys.CORRELATION_ID put correlationId.id
        }
        executionContext?.originDevice?.let { originDevice ->
            AuditDetailKeys.ORIGIN_DEVICE put originDevice.toString()
        }
        if (fault != null) OperationFaultDetails.FAULT put fault.displayType
    }
