package space.kscience.krig.dsl

import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextBuilder

/**
 * Minimal [Context] for inline SDK examples. It installs fail-closed authorization
 * and no-op audit so the runtime pipeline can be assembled without bringing in test
 * fixtures or production RBAC infrastructure.
 */
public fun scriptContext(name: String? = null, builder: ContextBuilder.() -> Unit = {}): Context =
    Context(name) {
        plugin(AuthorizationService)
        plugin(AuditService)
        builder()
    }
