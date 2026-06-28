package space.kscience.krig.jupyter

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextBuilder
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.NoOpAuditService

/**
 * Notebook/lab bootstrap context with permissive authorization and no-op audit.
 *
 * Core `scriptContext()` stays fail-closed. This helper is intentionally scoped to
 * `krig-jupyter`, where the default use case is interactive discovery rather than
 * production RBAC.
 */
public fun krigNotebookContext(
    name: String = "krig-notebook",
    builder: ContextBuilder.() -> Unit = {},
): Context = Context(name) {
    plugin(AllowAllAuthorizationService)
    plugin(NoOpAuditService)
    builder()
}
