package space.kscience.controls.core.capability

import kotlinx.coroutines.flow.MutableSharedFlow
import space.kscience.controls.api.events.ExecutionEvent
import space.kscience.controls.api.io.DeviceIO
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.device.DeviceCommand
import space.kscience.controls.core.device.DeviceEntity
import space.kscience.controls.core.state.PropertyRegistry
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Logger
import space.kscience.dataforge.context.info
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * A secure implementation of [CapabilityContext].
 * Prevents logic modules from accessing the raw [DeviceEntity] or closing the [DeviceIO].
 *
 * It acts as a firewall between the high-level logic (Capabilities) and the low-level Runtime.
 */
internal class CapabilitySandbox(
    private val device: DeviceEntity,
    private val driver: DeviceIO,
    private val messageBus: MutableSharedFlow<DeviceMessage>
) : CapabilityContext {

    override val context: Context get() = device.context

    @OptIn(InternalControlsApi::class)
    override val properties: PropertyRegistry get() = device.properties

    override val logger: Logger = device.logger

    override suspend fun publish(message: DeviceMessage) {
        messageBus.emit(message)
    }

    override suspend fun report(event: ExecutionEvent) {
        // TODO: In a full implementation, this would route to a TelemetryService.
        logger.info { "Telemetry Event: $event" }
    }

    /**
     * Safely requests a specific IO interface.
     * Uses [KClass.safeCast] to prevent ClassCastException if the driver doesn't support the feature.
     */
    override fun <T : Any> requireIO(type: KClass<T>): T {
        return type.safeCast(driver)
            ?: throw UnsupportedOperationException(
                "Driver of type '${driver::class.simpleName}' does not implement requested interface '${type.simpleName}'"
            )
    }

    /**
     * Allows capabilities (like FSM) to register action handlers dynamically.
     * This is internal to the runtime glue, not part of public Context API.
     */
    fun registerAction(name: Name, handler: suspend (Meta?) -> Meta?) {
        device.actionRegistry.register(name, handler)
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        device.sendCommand(command)
    }
}