package space.kscience.krig.core.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.ExecutionContext
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.context.executionContextOf
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.services.AuditDetailKeys
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.IdentityProvider
import space.kscience.krig.api.identifiers.CorrelationId

class AuditObserverTest {
    @Test
    fun auditObserverIncludesDelegatedExecutionContextDetails() = runTest {
        val audit = RecordingAuditService()
        val servicePrincipal = SimplePrincipal("service", roles = setOf("service"))
        val userPrincipal = SimplePrincipal("operator", roles = setOf("operator"))
        val observer = AuditObserver(
            hostName = "pump",
            auditService = audit,
            identityProvider = StaticIdentityProvider(servicePrincipal),
        )
        val descriptor = PropertyDescriptor(
            name = "rpm".asName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        val operationContext = OperationContext(OperationKinds.Write, descriptor.name, descriptor, "pump".asName())
        val executionContext = ExecutionContext(
            principal = servicePrincipal,
            correlationId = CorrelationId("trace-1"),
            originDevice = "controller".asName(),
            callerIdentity = "service-node",
            onBehalfOf = userPrincipal,
        )

        withContext(executionContextOf(executionContext)) {
            observer.observe(operationContext, durationNanos = 1, fault = null)
        }

        val record = audit.records.single()
        assertEquals(servicePrincipal, record.principal)
        assertEquals("device.write", record.action)
        assertEquals("service", record.details.stringAt(AuditDetailKeys.EXECUTING_PRINCIPAL))
        assertEquals("operator", record.details.stringAt(AuditDetailKeys.ON_BEHALF_OF))
        assertEquals("service-node", record.details.stringAt(AuditDetailKeys.CALLER_IDENTITY))
        assertEquals("trace-1", record.details.stringAt(AuditDetailKeys.CORRELATION_ID))
        assertEquals("controller", record.details.stringAt(AuditDetailKeys.ORIGIN_DEVICE))
    }

    private data class RecordedAudit(
        val principal: Principal,
        val action: String,
        val details: Meta,
    )

    private class RecordingAuditService : AbstractPlugin(Meta.EMPTY), AuditService {
        override val tag get() = AuditService.tag
        val records = mutableListOf<RecordedAudit>()

        override suspend fun record(principal: Principal, action: String, details: Meta) {
            records += RecordedAudit(principal, action, details)
        }
    }

    private class StaticIdentityProvider(
        private val principal: Principal,
    ) : AbstractPlugin(Meta.EMPTY), IdentityProvider {
        override val tag get() = IdentityProvider.tag

        override suspend fun resolve(identity: String?): Principal = principal
    }
}

private fun Meta.stringAt(name: Name): String? =
    get(name)?.string
