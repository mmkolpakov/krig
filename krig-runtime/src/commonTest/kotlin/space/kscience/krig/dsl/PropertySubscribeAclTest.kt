@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationException
import space.kscience.krig.api.services.AuthorizationService
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Per-property subscribe ACL: [space.kscience.krig.core.contracts.Device.subscribe] with a property
 * accepts a property-scoped grant or a device-wide grant, but never widens a property grant into a
 * device-wide one. Grants are modelled as the principal's roles holding permission ids.
 */
class PropertySubscribeAclTest {

    private object RoleBackedAuthorization : PluginFactory<AuthorizationService> {
        override val tag: PluginTag get() = AuthorizationService.tag
        override fun build(context: Context, meta: Meta): AuthorizationService =
            object : AbstractPlugin(meta), AuthorizationService {
                override suspend fun checkPermission(principal: Principal, permission: Permission) {
                    if (permission.id !in principal.roles) {
                        throw AuthorizationException("Permission '${permission.id}' denied for '${principal.name}'.")
                    }
                }
            }
    }

    private fun aclContext(name: String): Context = Context(name) {
        plugin(RoleBackedAuthorization)
        plugin(AuditService)
    }

    private suspend fun aclDevice(contextName: String) = device("d", aclContext(contextName)) {
        property("rpm") { 0.0 }
    }

    private val rpm = "rpm".asName()

    @Test
    fun propertyScopedGrantAuthorizesThatProperty() = runTest {
        val device = aclDevice("acl-prop-grant")
        val alice = SimplePrincipal("alice", roles = setOf("device.subscribe.d.rpm"))
        device.ensureAuthorized(alice, rpm)
    }

    @Test
    fun deviceWideGrantAuthorizesAnyProperty() = runTest {
        val device = aclDevice("acl-device-grant")
        val bob = SimplePrincipal("bob", roles = setOf("device.subscribe.d"))
        device.ensureAuthorized(bob, rpm)
    }

    @Test
    fun missingGrantIsRejected() = runTest {
        val device = aclDevice("acl-no-grant")
        val mallory = SimplePrincipal("mallory")
        assertFailsWith<AuthorizationException> { device.ensureAuthorized(mallory, rpm) }
    }

    @Test
    fun grantForAnotherPropertyIsRejected() = runTest {
        val device = aclDevice("acl-wrong-prop")
        val carol = SimplePrincipal("carol", roles = setOf("device.subscribe.d.temperature"))
        assertFailsWith<AuthorizationException> { device.ensureAuthorized(carol, rpm) }
    }

    @Test
    fun propertyGrantDoesNotWidenToDeviceWideSubscribe() = runTest {
        val device = aclDevice("acl-no-widen")
        val alice = SimplePrincipal("alice", roles = setOf("device.subscribe.d.rpm"))
        assertFailsWith<AuthorizationException> { device.ensureAuthorized(alice) }
    }
}
