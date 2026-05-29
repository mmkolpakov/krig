package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.TypeIds

/**
 * Protocol-neutral acquisition mapping.
 *
 * krig owns tags, timers, targets, and validation. Protocol modules own connectors:
 * connector libraries, files, sockets, vendor SDKs, and their address syntax.
 */
@Serializable
public data class DataAcquisitionConfiguration(
    public val sources: List<AcquisitionSourceSpec> = emptyList(),
    public val timers: List<AcquisitionTimerSpec> = emptyList(),
    public val tags: List<AcquisitionTagSpec> = emptyList(),
) {
    public companion object
}

public sealed interface DataAcquisitionLoadResult {
    public data class Valid(public val config: DataAcquisitionConfiguration) : DataAcquisitionLoadResult
    public data class Invalid(public val errors: List<String>) : DataAcquisitionLoadResult
}

/** Decodes [DataAcquisitionConfiguration] from a DataForge [Meta] document. */
public fun DataAcquisitionConfiguration.Companion.fromMeta(
    meta: Meta,
    lenient: Boolean = false,
): DataAcquisitionConfiguration =
    decodeConfigurationMeta(DataAcquisitionConfiguration.serializer(), meta, lenient)

/** Encodes this configuration into a DataForge [Meta] document. */
public fun DataAcquisitionConfiguration.toMeta(): Meta =
    encodeConfigurationMeta(DataAcquisitionConfiguration.serializer(), this)

/** Decodes and validates a [DataAcquisitionConfiguration] from [meta]. */
public fun DataAcquisitionConfiguration.Companion.load(
    meta: Meta,
    lenient: Boolean = false,
): DataAcquisitionLoadResult =
    runCatching { fromMeta(meta, lenient) }
        .fold(
            onSuccess = { config ->
                val errors = config.validate()
                if (errors.isEmpty()) DataAcquisitionLoadResult.Valid(config)
                else DataAcquisitionLoadResult.Invalid(errors)
            },
            onFailure = { error -> DataAcquisitionLoadResult.Invalid(listOf(error.message ?: error.toString())) },
        )

/** Opaque connector instance. [connector] is resolved by an external integration module. */
@Serializable
public data class AcquisitionSourceSpec(
    public val id: Name,
    public val connector: Name,
    public val config: Meta = Meta.EMPTY,
) {
    public constructor(
        id: Name,
        connector: String,
        config: Meta = Meta.EMPTY,
    ) : this(id, connector.asName(), config)

    init {
        require(id != Name.EMPTY) { "AcquisitionSourceSpec id must not be empty" }
        require(connector != Name.EMPTY) { "AcquisitionSourceSpec '$id' connector must not be empty" }
    }
}

/** Periodic sampling group. Timers refer to tag ids, not protocol addresses. */
@Serializable
public data class AcquisitionTimerSpec(
    public val id: Name,
    public val intervalMs: Long,
    public val tags: List<Name> = emptyList(),
) {
    init {
        require(id != Name.EMPTY) { "AcquisitionTimerSpec id must not be empty" }
        require(intervalMs > 0) { "AcquisitionTimerSpec '$id' intervalMs must be positive, got $intervalMs" }
    }
}

/**
 * One external tag mapped to an optional krig device property target.
 *
 * [address] is deliberately opaque. It may be a register, topic, path, node id, or any
 * other connector-owned string. The SDK validates references and type ids, not protocols.
 */
@Serializable
public data class AcquisitionTagSpec(
    public val id: Name,
    public val sourceId: Name,
    public val address: String,
    public val valueTypeId: String = TypeIds.META,
    public val target: AcquisitionTargetSpec? = null,
    public val timeoutMs: Long? = null,
    public val bufferCapacity: Int = 1024,
) {
    init {
        require(id != Name.EMPTY) { "AcquisitionTagSpec id must not be empty" }
        require(sourceId != Name.EMPTY) { "AcquisitionTagSpec '$id' sourceId must not be empty" }
        require(address.isNotBlank()) { "AcquisitionTagSpec '$id' address must not be blank" }
        require(valueTypeId.isNotBlank()) { "AcquisitionTagSpec '$id' valueTypeId must not be blank" }
        require(timeoutMs == null || timeoutMs > 0) { "AcquisitionTagSpec '$id' timeoutMs must be positive, got $timeoutMs" }
        require(bufferCapacity > 0) { "AcquisitionTagSpec '$id' bufferCapacity must be positive, got $bufferCapacity" }
    }
}

/** Target property in the krig device tree. */
@Serializable
public data class AcquisitionTargetSpec(
    public val deviceId: Name,
    public val property: Name,
) {
    init {
        require(deviceId != Name.EMPTY) { "AcquisitionTargetSpec deviceId must not be empty" }
        require(property != Name.EMPTY) { "AcquisitionTargetSpec property must not be empty" }
    }
}

/** Returns all structural errors. Empty means the plan is ready for an integration module. */
public fun DataAcquisitionConfiguration.validate(): List<String> = buildList {
    val sourceIds = sources.map { it.id }
    if (sourceIds.toSet().size != sourceIds.size) add("duplicate acquisition source id among $sourceIds")

    val timerIds = timers.map { it.id }
    if (timerIds.toSet().size != timerIds.size) add("duplicate acquisition timer id among $timerIds")

    val tagIds = tags.map { it.id }
    if (tagIds.toSet().size != tagIds.size) add("duplicate acquisition tag id among $tagIds")

    val sourceSet = sourceIds.toSet()
    tags.forEach { tag ->
        if (tag.sourceId !in sourceSet) {
            add("AcquisitionTagSpec '${tag.id}' references unknown sourceId '${tag.sourceId}'")
        }
    }

    val tagSet = tagIds.toSet()
    timers.forEach { timer ->
        timer.tags.forEach { tagId ->
            if (tagId !in tagSet) add("AcquisitionTimerSpec '${timer.id}' references unknown tagId '$tagId'")
        }
    }

    val targets = tags.mapNotNull { tag -> tag.target?.let { it.deviceId to it.property } }
    val duplicateTargets = targets.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    duplicateTargets.forEach { (deviceId, property) ->
        add("multiple acquisition tags target '$deviceId.$property'")
    }
}
