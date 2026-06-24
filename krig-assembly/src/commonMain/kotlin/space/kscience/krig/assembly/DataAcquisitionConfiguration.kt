package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.TypeId
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
): ConfigurationLoadResult<DataAcquisitionConfiguration> =
    loadConfigurationMeta(DataAcquisitionConfiguration.serializer(), meta, lenient) { it.validate() }

/** Built-in acquisition connector ids shipped by the SDK. */
public object AcquisitionConnectors {
    /**
     * Device-tree connector: each tag [address][AcquisitionTagSpec.address] names a property on the
     * source device. Resolved by [deviceTreeAcquisitionReader].
     */
    public val KrigDevice: Name = "krig.device".asName()
}

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
 * One external tag: a named, typed reference to a connector address.
 *
 * Acquisition is **ingress only** — it samples external sources into observations; routing a sample
 * into a device property is the topology layer's job (`deviceGroup` / `linkPeriodic`), not a field
 * here. [address] is deliberately opaque: a register, topic, path, node id, or any connector-owned
 * string. The SDK validates references and type ids, not protocols.
 */
@Serializable
public data class AcquisitionTagSpec(
    public val id: Name,
    public val sourceId: Name,
    public val address: String,
    public val valueTypeId: TypeId = TypeIds.META,
    public val timeoutMs: Long? = null,
    public val bufferCapacity: Int = 1024,
    public val reduction: ReductionSpec = ReductionSpec.Last,
) {
    init {
        require(id != Name.EMPTY) { "AcquisitionTagSpec id must not be empty" }
        require(sourceId != Name.EMPTY) { "AcquisitionTagSpec '$id' sourceId must not be empty" }
        require(address.isNotBlank()) { "AcquisitionTagSpec '$id' address must not be blank" }
        require(timeoutMs == null || timeoutMs > 0) { "AcquisitionTagSpec '$id' timeoutMs must be positive, got $timeoutMs" }
        require(bufferCapacity > 0) { "AcquisitionTagSpec '$id' bufferCapacity must be positive, got $bufferCapacity" }
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

}
