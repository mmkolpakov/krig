package space.kscience.krig.api.services

/**
 * Type-safe vocabulary of auditable actions.
 */
public interface AuditAction {
    /** Stable string identifier for wire serialization. */
    public val id: String

    public companion object {
        /** A property was read via the Control Plane. */
        public val DeviceRead: AuditAction = ActionOf("device.read", "AuditAction.DeviceRead")

        /** A property was written via the Control Plane. */
        public val DeviceWrite: AuditAction = ActionOf("device.write", "AuditAction.DeviceWrite")

        /** An action was executed via the Control Plane. */
        public val DeviceExecute: AuditAction = ActionOf("device.execute", "AuditAction.DeviceExecute")

        /** A principal subscribed to a device's message flow. */
        public val DeviceSubscribe: AuditAction = ActionOf("device.subscribe", "AuditAction.DeviceSubscribe")
    }
}

/** Internal helper — creates an AuditAction with stable toString(). */
private class ActionOf(override val id: String, private val name: String) : AuditAction {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = other is AuditAction && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
