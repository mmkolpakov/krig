package space.kscience.krig.api.services

import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.faults.DeviceSecurityException
import space.kscience.krig.api.identifiers.Permission
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * Authorization check service. If no implementation is provided, all actions are denied by default.
 */
public interface AuthorizationService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Checks if [principal] has the required [permission].
     * @throws DeviceSecurityException if the check fails.
     */
    public suspend fun checkPermission(principal: Principal, permission: Permission)

    public companion object : PluginFactory<AuthorizationService> {
        override val tag: PluginTag = PluginTag("device.authorization", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): AuthorizationService = DenyAllAuthorizationService(meta)
    }
}

/** Fail-safe default: denies all permission checks when no [AuthorizationService] is configured. */
private class DenyAllAuthorizationService(meta: Meta) : AbstractPlugin(meta), AuthorizationService {
    override val tag: PluginTag get() = AuthorizationService.tag

    override suspend fun checkPermission(principal: Principal, permission: Permission) {
        throw DeviceSecurityException(
            "Permission '${permission.id}' denied for principal '${principal.name}'. " +
                    "No specific AuthorizationService is configured; all actions are denied by default."
        )
    }
}


/** Gets the [AuthorizationService] from a context, or throws if not installed. */
public val Context.authorizationService: AuthorizationService
    get() = plugins.find(true) { it is AuthorizationService } as? AuthorizationService
        ?: error("AuthorizationService plugin is not installed in the context.")
