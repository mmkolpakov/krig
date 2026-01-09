package space.kscience.controls.core.factory

import kotlinx.serialization.PolymorphicSerializer
import space.kscience.controls.api.spec.RealtimePolicy
import space.kscience.controls.api.spec.TelemetryPolicy
import space.kscience.controls.api.structure.PropertyDescriptor
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.serialization.SerializationPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.context.request
import space.kscience.dataforge.context.warn
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.toJson
import space.kscience.dataforge.names.Name

/**
 * An internal helper responsible for resolving [TelemetryPolicy] configuration for device properties.
 *
 * It extracts the "telemetry" attribute from the property descriptor and deserializes it using
 * the polymorphic configuration provided by the [SerializationPlugin].
 *
 * Defaults to [RealtimePolicy] if configuration is missing or invalid.
 */
@InternalControlsApi
internal class TelemetryPolicyResolver(private val context: Context) {
    private val json by lazy { context.request(SerializationPlugin).json }

    private val policySerializer = PolymorphicSerializer(TelemetryPolicy::class)

    /**
     * Resolves policies for a list of properties.
     * The order of the returned array strictly matches the iteration order of the input list.
     *
     * @param properties The list of property entries (Name -> Descriptor).
     * @return An array of policies.
     */
    fun resolve(properties: List<Map.Entry<Name, PropertyDescriptor>>): Array<TelemetryPolicy> {
        return properties.map { (_, descriptor) ->
            parsePolicy(descriptor)
        }.toTypedArray()
    }

    private fun parsePolicy(descriptor: PropertyDescriptor): TelemetryPolicy {
        // 1. Check if "telemetry" attribute exists
        val policyMeta = descriptor.attributes["telemetry"] ?: return RealtimePolicy

        // 2. Attempt to deserialize polymorphic policy
        return try {
            json.decodeFromJsonElement(policySerializer, policyMeta.toJson())
        } catch (e: Exception) {
            // Fail-safe: Log warning and fallback to Realtime (safest option)
            context.logger.warn { "Failed to parse telemetry policy for property '${descriptor.name}': ${e.message}. Falling back to Realtime." }
            RealtimePolicy
        }
    }
}