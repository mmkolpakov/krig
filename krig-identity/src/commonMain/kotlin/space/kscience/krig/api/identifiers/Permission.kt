package space.kscience.krig.api.identifiers

/**
 * Permission required by [AuthorizationService][space.kscience.krig.api.services.AuthorizationService].
 * Open interface: FeatureSpec modules add their own types and back-ends handle them via a
 * terminal `else` branch.
 */
public interface Permission {
    /**
     * Stable wire identifier (e.g. `"device.write:reactor:setpoint"`).
     *
     * Logical fields are joined with `:`, and `\`/`:` inside a field are escaped with a backslash,
     * so hierarchical device names (`pump.main`) cannot collide across field boundaries: the pairs
     * (`a`, `b.c`) and (`a.b`, `c`) produce distinct ids.
     */
    public val id: String
}

private fun escapeField(field: String): String = buildString(field.length) {
    for (ch in field) {
        if (ch == '\\' || ch == ':') append('\\')
        append(ch)
    }
}

/** Joins [fields] into a collision-free permission id under the given [verb] prefix. */
private fun permissionId(verb: String, vararg fields: String): String =
    fields.joinToString(separator = ":", prefix = "$verb:") { escapeField(it) }

/** Built-in permission vocabulary. */
public interface ControlsPermission : Permission {

    public data class DeviceRead(val device: String, val property: String) : ControlsPermission {
        override val id: String = permissionId("device.read", device, property)
    }

    public data class DeviceWrite(val device: String, val property: String) : ControlsPermission {
        override val id: String = permissionId("device.write", device, property)
    }

    public data class DeviceExecute(val device: String, val action: String) : ControlsPermission {
        override val id: String = permissionId("device.execute", device, action)
    }

    public data class DeviceSubscribe(val device: String) : ControlsPermission {
        override val id: String = permissionId("device.subscribe", device)
    }

    public data class DevicePropertySubscribe(val device: String, val property: String) : ControlsPermission {
        override val id: String = permissionId("device.subscribe", device, property)
    }
}
