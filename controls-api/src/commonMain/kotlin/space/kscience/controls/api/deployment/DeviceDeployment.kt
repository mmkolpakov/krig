package space.kscience.controls.api.deployment

import space.kscience.controls.api.io.DeviceIO
import space.kscience.controls.api.structure.DeviceBlueprint

/**
 * A Transfer Object (DTO) representing a unit of deployment.
 * It encapsulates the intention to create a specific device instance.
 *
 * This object can bridge the gap between the static configuration (Blueprint) and the runtime environment (Driver).
 *
 * @property blueprint The "Data Plane" definition of the device (Manifest).
 * @property driver An optional "Control Plane" implementation of the hardware interface.
 *                  If null, the runtime handles driver instantiation.
 */
public class DeviceDeployment(
    public val blueprint: DeviceBlueprint,
    public val driver: DeviceIO? = null
)