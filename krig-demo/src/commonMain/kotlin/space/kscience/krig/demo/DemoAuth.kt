package space.kscience.krig.demo

import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.api.services.AuditService
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/** INSECURE - demo and test only. Permits every permission check. */
public class PermitAllAuthorizationService private constructor(meta: Meta) :
    AbstractPlugin(meta), AuthorizationService {
    override val tag get() = AuthorizationService.tag
    override suspend fun checkPermission(principal: Principal, permission: Permission) {}

    public companion object : PluginFactory<PermitAllAuthorizationService> {
        override val tag = AuthorizationService.tag
        override fun build(context: Context, meta: Meta) = PermitAllAuthorizationService(meta)
    }
}

/** Minimal Context for SDK demos with permissive auth + no-op audit. */
public fun demoContext(name: String? = null): Context = Context(name) {
    plugin(PermitAllAuthorizationService)
    plugin(AuditService)
}
