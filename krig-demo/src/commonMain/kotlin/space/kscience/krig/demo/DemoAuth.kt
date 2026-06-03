package space.kscience.krig.demo

import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.NoOpAuditService
import space.kscience.dataforge.context.Context

/** Minimal Context for SDK demos with permissive auth + no-op audit. */
fun demoContext(name: String? = null): Context = Context(name) {
    plugin(AllowAllAuthorizationService)
    plugin(NoOpAuditService)
}
