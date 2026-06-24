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
     * @param action Stable audit label, e.g. `"device.read"` or `"modbus.coil.write"`; dispatch on it.
     * @param details Optional structured metadata (device name, property, value, etc.).
     */
    public suspend fun record(principal: Principal, action: String, details: Meta = Meta.EMPTY)

    /** Fast-path check. When false, callers skip Meta construction for audit records. */
    public val isActive: Boolean get() = true

    public companion object : PluginFactory<AuditService> {
        override val tag: PluginTag = PluginTag("device.audit", group = PluginTag.DATAFORGE_GROUP)

        /** Default: no-op (system works without an audit backend). */
        override fun build(context: Context, meta: Meta): AuditService = NoOpAuditService.build(context, meta)
    }
}

/**
 * Reference no-op [AuditService] used when no audit backend is configured. Discards every record.
 * Install via `plugin(NoOpAuditService)` (or rely on the [AuditService] default).
 */
public class NoOpAuditService private constructor(meta: Meta) : AbstractPlugin(meta), AuditService {
    override val tag: PluginTag get() = AuditService.tag
    override val isActive: Boolean get() = false
    override suspend fun record(principal: Principal, action: String, details: Meta): Unit = Unit

    public companion object : PluginFactory<NoOpAuditService> {
        override val tag: PluginTag = AuditService.tag

        override fun build(context: Context, meta: Meta): NoOpAuditService = NoOpAuditService(meta)
    }
}

private val noOpFallback: AuditService = NoOpAuditService.build(Global, Meta.EMPTY)

/**
 * Gets the [AuditService] from a context. Falls back to a shared no-op instance if no service is
 * installed — this accessor sits on the `isActive` fast path before every audit record, so the
 * fallback must not allocate per call.
 */
public val Context.auditService: AuditService
    get() = plugins.find(true) { it is AuditService } as? AuditService
        ?: noOpFallback
