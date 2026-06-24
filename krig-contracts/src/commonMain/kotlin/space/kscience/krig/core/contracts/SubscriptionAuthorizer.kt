package space.kscience.krig.core.contracts

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.identifiers.ControlsPermission
import space.kscience.krig.api.services.AuthorizationException
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService

/**
 * Authorization and audit policy for device subscriptions, kept separate from the message
 * transport. A property-scoped grant suffices; a device-wide grant also covers every property.
 */
internal class SubscriptionAuthorizer(
    private val name: Name,
    private val context: Context,
) {
    /** Authorizes and audits a device-wide subscription. */
    suspend fun authorizeSubscribe(principal: Principal) {
        context.authorizationService.checkPermission(principal, ControlsPermission.DeviceSubscribe(name.toString()))
        audit(principal, property = null)
    }

    /** Authorizes and audits a property-scoped subscription, falling back to a device-wide grant. */
    suspend fun authorizePropertySubscribe(principal: Principal, property: Name) {
        val auth = context.authorizationService
        try {
            auth.checkPermission(principal, ControlsPermission.DevicePropertySubscribe(name.toString(), property.toString()))
        } catch (propertyDenied: AuthorizationException) {
            try {
                auth.checkPermission(principal, ControlsPermission.DeviceSubscribe(name.toString()))
            } catch (_: AuthorizationException) {
                throw propertyDenied
            }
        }
        audit(principal, property)
    }

    private suspend fun audit(principal: Principal, property: Name?) {
        if (!context.auditService.isActive) return
        context.auditService.record(
            principal,
            "device.subscribe",
            Meta {
                OperationFaultDetails.DEVICE put name.toString()
                if (property != null) "property" put property.toString()
            },
        )
    }
}
