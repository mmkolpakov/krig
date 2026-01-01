package space.kscience.controls.services

import kotlinx.coroutines.flow.SharedFlow
import space.kscience.controls.core.events.ExecutionEvent
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a service that provides a centralized, hot stream of telemetry events ([ExecutionEvent]).
 * This service is distinct from the [MessageBroker], as it focuses on the *process* of execution
 * (e.g., timing, cache hits) rather than state changes.
 *
 * The runtime will provide an implementation that collects events from various components
 * (like the hub and device actors) and broadcasts them. External monitoring systems, such as
 * an OpenTelemetry exporter, can subscribe to this flow to build traces and detailed metrics.
 */
public interface TelemetryService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * A hot, shared flow of all telemetry events occurring within the context.
     */
    public val events: SharedFlow<ExecutionEvent>

    public companion object : PluginFactory<TelemetryService> {
        override val tag: PluginTag = PluginTag("device.telemetry", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default factory throws an error, as a concrete implementation must be provided by a runtime module.
         */
        override fun build(context: Context, meta: Meta): TelemetryService {
            error("TelemetryService is a service interface and requires a runtime-specific implementation.")
        }
    }
}