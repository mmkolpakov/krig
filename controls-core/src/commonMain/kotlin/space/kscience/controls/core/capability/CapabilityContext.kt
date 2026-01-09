package space.kscience.controls.core.capability

import space.kscience.controls.api.events.ExecutionEvent
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.device.DeviceCommand
import space.kscience.controls.core.state.PropertyRegistry
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.context.Logger
import kotlin.reflect.KClass

/**
 * A restricted facade provided to [Capability] instances.
 *
 * Capabilities should generally be stateless and interact with the device
 * through this context.
 */
public interface CapabilityContext : ContextAware {
    /**
     * Access to the device's property registry.
     *
     * **Warning:** While [PropertyRegistry] may expose update methods, Capabilities
     * should generally treat this as **Read-Only** for physical properties.
     * Writing to physical properties should be done by sending commands to the device actor
     * (methods for which should be exposed by extensions or specialized interfaces),
     * otherwise the physical state and the memory state will drift apart.
     */
    @OptIn(InternalControlsApi::class)
    public val properties: PropertyRegistry

    /**
     * A device-scoped logger.
     */
    public val logger: Logger

    /**
     * Publishes a message to the device's output bus.
     * This is the mechanism for Capabilities to communicate with the outside world (Hub, UI, other devices).
     */
    public suspend fun publish(message: DeviceMessage)

    /**
     * Reports an operational event (telemetry/tracing).
     */
    public suspend fun report(event: ExecutionEvent)

    /**
     * Requests access to a specific hardware interface implemented by the driver.
     * Throws an exception if the driver does not support this interface.
     *
     * This is the "Escape Hatch" for capabilities that need raw IO access (e.g. StreamIO).
     * Use with caution.
     *
     * @param type The class of the IO interface (e.g. `ScalarInputIO::class`).
     */
    public fun <T : Any> requireIO(type: KClass<T>): T

    public suspend fun sendCommand(command: DeviceCommand)
}