package space.kscience.controls.services

import space.kscience.controls.api.context.Principal
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a service that logs security-relevant and operational events for auditing purposes.
 * The runtime should provide an implementation that can forward audit records to a secure log,
 * a database, or an external monitoring system.
 */
public interface AuditService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Records an audit event. This operation should be fast and non-blocking from the caller's perspective.
     *
     * @param principal The principal who performed the action.
     * @param action A URN-style string identifying the action (e.g., "device:writeProperty", "security:login").
     * @param details A [Meta] object containing context-specific details about the event.
     */
    public suspend fun record(principal: Principal, action: String, details: Meta = Meta.EMPTY)

    public companion object : PluginFactory<AuditService> {
        override val tag: PluginTag = PluginTag("device.audit", group = PluginTag.DATAFORGE_GROUP)

        /**
         * Builds a default no-op implementation of [AuditService].
         * This ensures that the system can function without an audit backend configured.
         */
        override fun build(context: Context, meta: Meta): AuditService = NoOpAuditService(meta)
    }
}

/**
 * A default, no-op implementation of [AuditService] that does nothing.
 * It is used when no specific audit service is configured in the context.
 */
private class NoOpAuditService(meta: Meta) : AbstractPlugin(meta), AuditService {
    override val tag: PluginTag get() = AuditService.tag
    override suspend fun record(principal: Principal, action: String, details: Meta) {
        // No operation
    }
}