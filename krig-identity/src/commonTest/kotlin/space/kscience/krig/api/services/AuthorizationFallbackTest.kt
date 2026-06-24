package space.kscience.krig.api.services

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.identifiers.ControlsPermission
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Pins the documented fallback semantics: a context without an [AuthorizationService] plugin denies
 * every action with [AuthorizationException] (never [IllegalStateException]), and the audit fallback
 * is an allocation-free shared no-op.
 */
class AuthorizationFallbackTest {

    @Test
    fun missingAuthorizationPluginDeniesWithAuthorizationException() = runTest {
        val context = Context("authz-fallback")
        val principal = SimplePrincipal("alice")

        assertFailsWith<AuthorizationException> {
            context.authorizationService.checkPermission(
                principal,
                ControlsPermission.DeviceRead("pump", "rpm"),
            )
        }
    }

    @Test
    fun missingAuditPluginFallsBackToSharedNoOp() {
        val context = Context("audit-fallback")

        val first = context.auditService
        val second = context.auditService

        assertFalse(first.isActive, "fallback audit must be inactive")
        assertSame(first, second, "fallback must be a shared instance, not allocated per access")
    }
}
