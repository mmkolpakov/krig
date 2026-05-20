package space.kscience.krig.api.identifiers

/**
 * Permission required by [AuthorizationService][space.kscience.krig.api.services.AuthorizationService].
 * Open interface: FeatureSpec modules add their own types and back-ends handle them via a
 * terminal `else` branch.
 */
public interface Permission {
    /** Stable wire identifier (e.g. `"device.write.reactor.setpoint"`). */
    public val id: String
}

/** Built-in permission vocabulary. */
public interface ControlsPermission : Permission {

    public data class DeviceRead(val device: String, val property: String) : ControlsPermission {
        override val id: String = "device.read.$device.$property"
    }

    public data class DeviceWrite(val device: String, val property: String) : ControlsPermission {
        override val id: String = "device.write.$device.$property"
    }

    public data class DeviceExecute(val device: String, val action: String) : ControlsPermission {
        override val id: String = "device.execute.$device.$action"
    }

    public data class DeviceSubscribe(val device: String) : ControlsPermission {
        override val id: String = "device.subscribe.$device"
    }
}
