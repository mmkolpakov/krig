package space.kscience.krig.assembly

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * Declarative runtime topology: devices to instantiate, timers to attach, properties to sample.
 *
 * Assembly descriptor for KRig manifests. External protocol acquisition plans use
 * [DataAcquisitionConfiguration], which keeps connector-specific addressing outside
 * the SDK core.
 */
@Serializable
public data class DataPlatformConfiguration(
    val sources: List<SourceSpec> = emptyList(),
    val timers: List<TimerSpec> = emptyList(),
    val properties: List<PropertySpec> = emptyList(),
) {
    public companion object
}

public sealed interface DataPlatformLoadResult {
    public data class Valid(public val config: DataPlatformConfiguration) : DataPlatformLoadResult
    public data class Invalid(public val errors: List<String>) : DataPlatformLoadResult
}

/** Decodes [DataPlatformConfiguration] from a DataForge [Meta] document. */
public fun DataPlatformConfiguration.Companion.fromMeta(
    meta: Meta,
    lenient: Boolean = false,
): DataPlatformConfiguration =
    decodeConfigurationMeta(DataPlatformConfiguration.serializer(), meta, lenient)

/** Encodes this configuration into a DataForge [Meta] document. */
public fun DataPlatformConfiguration.toMeta(): Meta =
    encodeConfigurationMeta(DataPlatformConfiguration.serializer(), this)

/** Decodes and validates a [DataPlatformConfiguration] from [meta]. */
public fun DataPlatformConfiguration.Companion.load(meta: Meta, lenient: Boolean = false): DataPlatformLoadResult =
    runCatching { fromMeta(meta, lenient) }
        .fold(
            onSuccess = { config ->
                val errors = config.validate()
                if (errors.isEmpty()) DataPlatformLoadResult.Valid(config)
                else DataPlatformLoadResult.Invalid(errors)
            },
            onFailure = { error -> DataPlatformLoadResult.Invalid(listOf(error.message ?: error.toString())) },
        )

/** Reduction applied when a collector has to collapse several values into one grid bin. */
@Serializable
public sealed interface ReductionSpec {
    public val id: Name

    @Serializable
    @SerialName("last")
    public data object Last : ReductionSpec {
        override val id: Name = "last".asName()
    }

    @Serializable
    @SerialName("mean")
    public data object Mean : ReductionSpec {
        override val id: Name = "mean".asName()
    }

    @Serializable
    @SerialName("minMaxMean")
    public data object MinMaxMean : ReductionSpec {
        override val id: Name = "minMaxMean".asName()
    }

    @Serializable
    @SerialName("named")
    public data class Named(
        val name: Name,
        val config: Meta = Meta.EMPTY,
    ) : ReductionSpec {
        override val id: Name get() = name
    }
}

/**
 * Describes a device instance to be created from a registered [DeviceManifest].
 *
 * @property id Runtime name of the instance within the hub's device tree.
 * @property manifestId Matches a [DeviceManifest.id] registered in the [DeviceCatalog].
 * @property config Opaque per-instance configuration, threaded to the Manifest's builder.
 */
@Serializable
public data class SourceSpec(
    val id: Name,
    val manifestId: Name,
    val config: Meta = Meta.EMPTY,
) {
    init {
        require(id != Name.EMPTY) { "SourceSpec id must not be empty" }
        require(manifestId != Name.EMPTY) { "SourceSpec '$id' manifestId must not be empty" }
    }
}

/** Timer that drives property sampling. */
@Serializable
public data class TimerSpec(
    val id: Name,
    val intervalMs: Long,
    val properties: List<Name> = emptyList(),
) {
    init {
        require(id != Name.EMPTY) { "TimerSpec id must not be empty" }
        require(intervalMs > 0) { "TimerSpec '$id' intervalMs must be positive, got $intervalMs" }
    }
}

/**
 * Binds a single property on a configured [SourceSpec] to a time-series collector.
 *
 * @property reduction Reduction applied to each grid bin.
 */
@Serializable
public data class PropertySpec(
    val id: Name,
    val sourceId: Name,
    val property: Name,
    val reduction: ReductionSpec = ReductionSpec.Last,
    val bufferCapacity: Int = 1024,
) {
    init {
        require(id != Name.EMPTY) { "PropertySpec id must not be empty" }
        require(sourceId != Name.EMPTY) { "PropertySpec '$id' sourceId must not be empty" }
        require(property != Name.EMPTY) { "PropertySpec '$id' property must not be empty" }
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
 * Resolves every [SourceSpec.manifestId] against the installed [DeviceCatalog] and
 * returns the matching Manifest map. Missing manifests surface as an exception so the
 * caller can short-circuit before starting any device.
 */
public fun Context.resolveManifests(
    config: DataPlatformConfiguration,
): Map<Name, DeviceManifest> {
    val catalog = deviceCatalog()
    val missing = mutableListOf<Name>()
    val resolved = buildMap {
        config.sources.forEach { spec ->
            val manifest = catalog[spec.manifestId]
            if (manifest == null) missing += spec.manifestId else put(spec.id, manifest)
        }
    }
    if (missing.isNotEmpty()) {
        error("DataPlatformConfiguration references manifests that are not registered: $missing")
    }
    return resolved
}

/**
 * Fully-qualified device name derived from [SourceSpec.id]. Uses DataForge name parsing so
 * hierarchical ids (`hub.sector.motor`) map to tree paths.
 */
public val SourceSpec.deviceName: Name get() = id
