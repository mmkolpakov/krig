package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.firstOrNull
import space.kscience.dataforge.names.parseAsName
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

/**
 * How a source-level, coalesced read derives one timeout from tags with individual budgets.
 *
 * A batch read cannot cancel one physical operation per tag, so the policy is explicit:
 * [TightestTag] preserves every tag SLA at the cost of failing slow batch members,
 * [SlowestTag] lets the whole batch use the largest declared budget, and [Unbounded] delegates
 * timeout handling completely to the connector.
 */
@Serializable
public enum class BatchTimeoutPolicy {
    @SerialName("tightest-tag")
    TightestTag,

    @SerialName("slowest-tag")
    SlowestTag,

    @SerialName("unbounded")
    Unbounded,
}

/** Resolves the source-level timeout in milliseconds for a coalesced read of [tags]. */
public fun BatchTimeoutPolicy.resolveBatchTimeoutMs(tags: List<AcquisitionTagSpec>): Long? {
    val declared = tags.mapNotNull { it.timeoutMs }
    return when (this) {
        BatchTimeoutPolicy.TightestTag -> declared.minOrNull()
        BatchTimeoutPolicy.SlowestTag -> declared.maxOrNull()
        BatchTimeoutPolicy.Unbounded -> null
    }
}

/**
 * Opaque connector instance. [id] is the acquisition source id used by tags and timers; it is not a
 * topology path. Connectors that need a hierarchical KRig device path use [topologyPath]. When a
 * `krig.device` source omits [topologyPath], the SDK also accepts the historical single-token
 * projection from [id] via [Name.asAcquisitionTopologyPath].
 */
@Serializable
public data class AcquisitionSourceSpec(
    public val id: Name,
    public val connector: Name,
    public val config: Meta = Meta.EMPTY,
    public val topologyPath: Name? = null,
    public val batchTimeoutPolicy: BatchTimeoutPolicy = BatchTimeoutPolicy.SlowestTag,
) {
    public constructor(
        id: Name,
        connector: String,
        config: Meta = Meta.EMPTY,
        topologyPath: Name? = null,
        batchTimeoutPolicy: BatchTimeoutPolicy = BatchTimeoutPolicy.SlowestTag,
    ) : this(id, connector.asName(), config, topologyPath, batchTimeoutPolicy)

    init {
        require(id != Name.EMPTY) { "AcquisitionSourceSpec id must not be empty" }
        require(connector != Name.EMPTY) { "AcquisitionSourceSpec '$id' connector must not be empty" }
        require(topologyPath != Name.EMPTY) { "AcquisitionSourceSpec '$id' topologyPath must not be empty" }
    }
}

/**
 * Single-token acquisition source id convention for a hierarchical topology [Name]. The body is the
 * rendered topology path; consumers must treat it as an opaque id, not parse it as a `Name`.
 */
public fun Name.toAcquisitionSourceId(): Name = toString().asName()

/**
 * Historical `krig.device` convention: a single-token source id such as `"plant.main".asName()` is
 * interpreted as a topology path when a dedicated [AcquisitionSourceSpec.topologyPath] is absent.
 * Multi-token ids are already paths and pass through unchanged.
 */
public fun Name.asAcquisitionTopologyPath(): Name =
    if (tokens.size == 1) firstOrNull()!!.body.parseAsName() else this

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
