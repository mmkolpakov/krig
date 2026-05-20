package space.kscience.krig.demo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.context.ExecutionContext
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.context.executionContextOf
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.services.AuthorizationException
import space.kscience.krig.api.services.AuditAction
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.api.services.auditService
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.dsl.device

/**
 * Principal-aware write gate producing fault values.
 */
suspend fun authAuditDemo() {
    val ctx = Context("auth-audit-demo") {
        plugin(OperatorOnlyAuthorizationService)
        plugin(RecordingAuditService)
    }
    val audit = ctx.auditService as RecordingAuditService
    val pump = device("securePump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }
    val guest = SimplePrincipal("guest")
    val operator = SimplePrincipal("operator", roles = setOf("operator"))

    println("=== Auth and audit ===")
    val guestResult = withContext(executionContextOf(ExecutionContext(guest))) {
        pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(1_000.0))
    }
    val operatorResult = withContext(executionContextOf(ExecutionContext(operator))) {
        pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(1_000.0))
    }
    withTimeout(1.seconds) {
        while (audit.records.size < 2) yield()
    }
    println("  guest write: ${guestResult.outcomeLabel()}")
    println("  operator write: ${operatorResult.outcomeLabel()}")
    println("  audit records: ${audit.records.map { it.label }}")

    pump.close()
    ctx.close()
    println("\nDone - auth and audit demo complete.")
}

private class OperatorOnlyAuthorizationService private constructor(meta: Meta) :
    AbstractPlugin(meta),
    AuthorizationService {
    override val tag: PluginTag get() = AuthorizationService.tag

    override suspend fun checkPermission(principal: Principal, permission: Permission) {
        if ("operator" !in principal.roles) {
            throw AuthorizationException("Permission '${permission.id}' denied for '${principal.name}'.")
        }
    }

    companion object : PluginFactory<OperatorOnlyAuthorizationService> {
        override val tag: PluginTag = AuthorizationService.tag
        override fun build(context: Context, meta: Meta): OperatorOnlyAuthorizationService =
            OperatorOnlyAuthorizationService(meta)
    }
}

private data class DemoAuditRecord(
    val label: String,
)

private class RecordingAuditService private constructor(meta: Meta) :
    AbstractPlugin(meta),
    AuditService {
    override val tag: PluginTag get() = AuditService.tag
    val records: MutableList<DemoAuditRecord> = mutableListOf()

    override suspend fun record(principal: Principal, action: AuditAction, details: Meta) {
        records += DemoAuditRecord("${principal.name}:${action.id}:${details}")
    }

    companion object : PluginFactory<RecordingAuditService> {
        override val tag: PluginTag = AuditService.tag
        override fun build(context: Context, meta: Meta): RecordingAuditService =
            RecordingAuditService(meta)
    }
}

private fun OperationOutcome<*>.outcomeLabel(): String = when (this) {
    is OperationOutcome.Ok -> "ok"
    is OperationOutcome.Fail -> "fail:${fault.faultType}"
}
