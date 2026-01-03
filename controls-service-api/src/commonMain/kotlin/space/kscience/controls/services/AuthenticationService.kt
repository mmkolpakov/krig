package space.kscience.controls.services

import space.kscience.controls.core.faults.DeviceSecurityException
import space.kscience.controls.api.context.Principal
import space.kscience.controls.api.identifiers.Permission
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a service that handles authorization checks.
 * The runtime is responsible for providing a concrete implementation of this service,
 * for example, based on a roles database, JWT tokens, or a simple configuration file.
 *
 * If no specific implementation is provided in the context, a default secure implementation
 * that denies all actions will be used.
 */
public interface AuthorizationService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Checks if the given [Principal] has the required [Permission].
     * Throws a [DeviceSecurityException] if the check fails.
     *
     * @param principal The identity of the actor attempting the operation.
     * @param permission The permission required for the operation.
     */
    public suspend fun checkPermission(principal: Principal, permission: Permission)

    public companion object : PluginFactory<AuthorizationService> {
        override val tag: PluginTag = PluginTag("device.authorization", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): AuthorizationService = DenyAllAuthorizationService(meta)
    }
}

/**
 * A default, secure implementation of [AuthorizationService] that denies all permission checks.
 * This ensures that if no specific authorization logic is configured, the system fails safely.
 */
private class DenyAllAuthorizationService(meta: Meta) : AbstractPlugin(meta), AuthorizationService {
    override val tag: PluginTag get() = AuthorizationService.tag

    override suspend fun checkPermission(principal: Principal, permission: Permission) {
        throw DeviceSecurityException(
            "Permission '${permission.id}' denied for principal '${principal.name}'. " +
                    "No specific AuthorizationService is configured; all actions are denied by default."
        )
    }
}


/**
 * Convenience extension to get the [AuthorizationService] from a context.
 */
public val Context.authorizationService: AuthorizationService
    get() = plugins.find(true) { it is AuthorizationService } as? AuthorizationService
        ?: error("AuthorizationService plugin is not installed in the context.")