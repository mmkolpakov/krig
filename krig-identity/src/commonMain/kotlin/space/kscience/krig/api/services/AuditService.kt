package space.kscience.krig.api.services

import space.kscience.krig.api.context.Principal
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/** Logs Principal-attributed security and operational events for auditing. */
public interface AuditService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Records an audit event. Should be fast and non-blocking.
     *
     * @param principal The authenticated identity that triggered the action.
     * @param action A [sealed][AuditAction] variant identifying the operation.
     *               Exhaustive `when` in backend implementations guarantees
     *               new action types are handled at compile time.
     * @param details Optional structured metadata (device name, property, value, etc.).
     */
    public suspend fun record(principal: Principal, action: AuditAction, details: Meta = Meta.EMPTY)

    /** Fast-path check. When false, callers skip Meta construction for audit records. */
    public val isActive: Boolean get() = true

    public companion object : PluginFactory<AuditService> {
        override val tag: PluginTag = PluginTag("device.audit", group = PluginTag.DATAFORGE_GROUP)

        /** Default: no-op (system works without an audit backend). */
        override fun build(context: Context, meta: Meta): AuditService = NoOpAuditService(meta)
    }
}

/** No-op [AuditService] used when no audit backend is configured. */
private class NoOpAuditService(meta: Meta) : AbstractPlugin(meta), AuditService {
    override val tag: PluginTag get() = AuditService.tag
    override val isActive: Boolean get() = false
    override suspend fun record(principal: Principal, action: AuditAction, details: Meta): Unit = Unit
}

/** Gets the [AuditService] from a context. Falls back to a no-op if no service is installed. */
public val Context.auditService: AuditService
    get() = plugins.find(true) { it is AuditService } as? AuditService
        ?: AuditService.build(this, Meta.EMPTY)
