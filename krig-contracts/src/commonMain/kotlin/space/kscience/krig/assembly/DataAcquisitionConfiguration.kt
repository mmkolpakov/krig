package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    public companion object {
        public fun parse(text: String, json: Json = StrictJson): DataAcquisitionConfiguration =
            json.decodeFromString(serializer(), text)

        public fun parseLenient(text: String): DataAcquisitionConfiguration =
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

/** Opaque connector instance. [connector] is resolved by an external integration module. */
@Serializable
public data class AcquisitionSourceSpec(
    public val id: String,
    public val connector: String,
    public val config: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(id.isNotBlank()) { "AcquisitionSourceSpec id must not be blank" }
        require(connector.isNotBlank()) { "AcquisitionSourceSpec '$id' connector must not be blank" }
        config.keys.forEach { key ->
            require(key.isNotBlank()) { "AcquisitionSourceSpec '$id' config key must not be blank" }
        }
    }
}

/** Periodic sampling group. Timers refer to tag ids, not protocol addresses. */
@Serializable
public data class AcquisitionTimerSpec(
    public val id: String,
    public val intervalMs: Long,
    public val tags: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "AcquisitionTimerSpec id must not be blank" }
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
    public val id: String,
    public val sourceId: String,
    public val address: String,
    public val valueTypeId: String = TypeIds.META,
    public val target: AcquisitionTargetSpec? = null,
    public val timeoutMs: Long? = null,
    public val bufferCapacity: Int = 1024,
) {
    init {
        require(id.isNotBlank()) { "AcquisitionTagSpec id must not be blank" }
        require(sourceId.isNotBlank()) { "AcquisitionTagSpec '$id' sourceId must not be blank" }
        require(address.isNotBlank()) { "AcquisitionTagSpec '$id' address must not be blank" }
        require(valueTypeId.isNotBlank()) { "AcquisitionTagSpec '$id' valueTypeId must not be blank" }
        require(timeoutMs == null || timeoutMs > 0) { "AcquisitionTagSpec '$id' timeoutMs must be positive, got $timeoutMs" }
        require(bufferCapacity > 0) { "AcquisitionTagSpec '$id' bufferCapacity must be positive, got $bufferCapacity" }
    }
}

/** Target property in the krig device tree. */
@Serializable
public data class AcquisitionTargetSpec(
    public val deviceId: String,
    public val property: String,
) {
    init {
        require(deviceId.isNotBlank()) { "AcquisitionTargetSpec deviceId must not be blank" }
        require(property.isNotBlank()) { "AcquisitionTargetSpec property must not be blank" }
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
