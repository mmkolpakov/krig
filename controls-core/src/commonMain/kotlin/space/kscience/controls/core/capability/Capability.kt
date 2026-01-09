package space.kscience.controls.core.capability

import kotlinx.coroutines.CoroutineScope
import space.kscience.controls.api.events.ExecutionEvent
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.meta.FeatureSpec
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.state.PropertyRegistry
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.context.Logger
import space.kscience.dataforge.meta.Meta
import kotlin.reflect.KClass

/**
 * A unit of logic attached to a [space.kscience.controls.core.device.DeviceEntity].
 *
 * Capabilities are stateless regarding the device configuration (they receive configuration via [reconfigure]).
 * They operate within the provided [CapabilityContext].
 */
public interface Capability {
    /**
     * Called when the capability is first created and attached to the device.
     * Use this for one-time initialization (e.g., registering listeners).
     *
     * @param context The restricted context for interacting with the device.
     */
    public fun attach(context: CapabilityContext)

    /**
     * Called when the device starts (Active Phase).
     * Coroutines launched here should use the scope provided by the context or the device.
     */
    public suspend fun start()

    /**
     * Called when the device stops.
     * Clean up resources, close connections, etc.
     */
    public suspend fun stop()

    /**
     * dynamic reconfiguration hook.
     *
     * @param meta The new configuration (immutable snapshot).
     */
    public suspend fun reconfigure(meta: Meta)
}