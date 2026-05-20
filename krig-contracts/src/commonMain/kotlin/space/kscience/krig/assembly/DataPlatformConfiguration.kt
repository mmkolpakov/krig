package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName

/**
 * Declarative runtime topology: devices to instantiate, timers to attach, properties to sample.
 *
 * This is an assembly descriptor for krig blueprints. External protocol acquisition
 * plans use [DataAcquisitionConfiguration], which keeps connector-specific addressing
 * outside the SDK core.
 *
 * ```json
 * {
 *   "sources":    [{ "id": "motor.x", "blueprintId": "simulated.motor", "config": {} }],
 *   "timers":     [{ "id": "fast", "intervalMs": 50, "properties": ["motor.x.position"] }],
 *   "properties": [{ "id": "motor.x.position", "sourceId": "motor.x", "property": "pv" }]
 * }
 * ```
 */
@Serializable
public data class DataPlatformConfiguration(
    val sources: List<SourceSpec> = emptyList(),
    val timers: List<TimerSpec> = emptyList(),
    val properties: List<PropertySpec> = emptyList(),
) {
    public companion object {
        /**
         * Decodes a configuration document from [text]. Unknown keys are rejected by
         * default so deployment typos fail during validation, not on a running stand.
         */
        public fun parse(text: String, json: Json = StrictJson): DataPlatformConfiguration =
            json.decodeFromString(serializer(), text)

        /** Decodes with unknown-key tolerance for explicit migration tooling only. */
        public fun parseLenient(text: String): DataPlatformConfiguration =
            parse(text, LenientJson)

        internal val StrictJson: Json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = false
            explicitNulls = false
        }

        internal val LenientJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}

/**
 * Describes a device instance to be created from a registered [DeviceBlueprint].
 *
 * @property id Runtime name of the instance within the hub's device tree.
 * @property blueprintId Matches a [DeviceBlueprint.id] registered in the [BlueprintPlugin].
 * @property config Opaque per-instance configuration, threaded to the blueprint's builder.
 */
@Serializable
public data class SourceSpec(
    val id: String,
    val blueprintId: String,
    val config: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(id.isNotBlank()) { "SourceSpec id must not be blank" }
        require(blueprintId.isNotBlank()) { "SourceSpec '$id' blueprintId must not be blank" }
        config.keys.forEach { key ->
            require(key.isNotBlank()) { "SourceSpec '$id' config key must not be blank" }
        }
    }
}

/** Wall-clock timer that drives property sampling on every tick. */
@Serializable
public data class TimerSpec(
    val id: String,
    val intervalMs: Long,
    val properties: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "TimerSpec id must not be blank" }
        require(intervalMs > 0) { "TimerSpec '$id' intervalMs must be positive, got $intervalMs" }
    }
}

/**
 * Binds a single property on a configured [SourceSpec] to a time-series collector.
 *
 * @property reduce Named reduction applied to each grid bin. Recognised keys:
 *                  `LastValue`, `MinMaxMean`, `Mean`. Unknown keys fall back to `LastValue`
 *                  with a runtime warning.
 */
@Serializable
public data class PropertySpec(
    val id: String,
    val sourceId: String,
    val property: String,
    val reduce: String = "LastValue",
    val bufferCapacity: Int = 1024,
) {
    init {
        require(id.isNotBlank()) { "PropertySpec id must not be blank" }
        require(sourceId.isNotBlank()) { "PropertySpec '$id' sourceId must not be blank" }
        require(property.isNotBlank()) { "PropertySpec '$id' property must not be blank" }
        require(reduce.isNotBlank()) { "PropertySpec '$id' reduce must not be blank" }
        require(bufferCapacity > 0) {
            "PropertySpec '$id' bufferCapacity must be positive, got $bufferCapacity"
        }
    }
}

/**
 * Validates the configuration's cross-references. Returns a non-empty list of human-readable
 * error messages when the config is inconsistent; an empty list means ready to install.
 */
public fun DataPlatformConfiguration.validate(): List<String> = buildList {
    val sourceIds = sources.map { it.id }.toSet()
    if (sourceIds.size != sources.size) {
        add("duplicate sourceId among ${sources.map { it.id }}")
    }

    val propertyIds = properties.map { it.id }.toSet()
    if (propertyIds.size != properties.size) {
        add("duplicate propertyId among ${properties.map { it.id }}")
    }

    properties.forEach { prop ->
        if (prop.sourceId !in sourceIds) {
            add("PropertySpec '${prop.id}' references unknown sourceId '${prop.sourceId}'")
        }
    }

    timers.forEach { timer ->
        timer.properties.forEach { propId ->
            if (propId !in propertyIds) {
                add("TimerSpec '${timer.id}' references unknown propertyId '$propId'")
            }
        }
    }
}

/**
 * Resolves every [SourceSpec.blueprintId] against the installed [BlueprintPlugin] and
 * returns the matching blueprint map. Missing blueprints surface as an exception so the
 * caller can short-circuit before starting any device.
 */
public fun Context.resolveBlueprints(
    config: DataPlatformConfiguration,
): Map<String, DeviceBlueprint<*>> {
    val plugin = blueprints()
    val missing = mutableListOf<String>()
    val resolved = buildMap {
        config.sources.forEach { spec ->
            val bp = plugin[spec.blueprintId.parseAsName()]
            if (bp == null) missing += spec.blueprintId else put(spec.id, bp)
        }
    }
    if (missing.isNotEmpty()) {
        error("DataPlatformConfiguration references blueprints that are not registered: $missing")
    }
    return resolved
}

/**
 * Fully-qualified device name derived from [SourceSpec.id]. Uses DataForge name parsing so
 * hierarchical ids (`hub.sector.motor`) map to tree paths.
 */
public val SourceSpec.deviceName: Name get() = id.parseAsName()
