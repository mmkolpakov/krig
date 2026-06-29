package space.kscience.krig.assembly

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import kotlin.time.Clock

/**
 * User-facing tag-table view over [DataAcquisitionConfiguration].
 *
 * The configuration remains the single source of truth: sources, timers and tags are still validated
 * and executed by the acquisition layer. This facade adds SCADA/lab-style lookup rows, manifest
 * projection and demo readers without creating a second configuration DTO.
 */
public class TagTable(
    public val configuration: DataAcquisitionConfiguration,
) {
    private val validationErrors: List<String> = configuration.validate()
    private val sourcesById: Map<Name, AcquisitionSourceSpec> = configuration.sources.associateBy { it.id }
    private val timersById: Map<Name, AcquisitionTimerSpec> = configuration.timers.associateBy { it.id }
    private val tagsById: Map<Name, AcquisitionTagSpec> = configuration.tags.associateBy { it.id }

    init {
        require(validationErrors.isEmpty()) {
            validationErrors.joinToString(separator = "; ", prefix = "Invalid tag table: ")
        }
    }

    /** Declared acquisition sources. */
    public val sources: List<AcquisitionSourceSpec> get() = configuration.sources

    /** Declared acquisition timers. */
    public val timers: List<AcquisitionTimerSpec> get() = configuration.timers

    /** Declared tags. */
    public val tags: List<AcquisitionTagSpec> get() = configuration.tags

    /** Rows keyed by tag id, preserving declaration order. */
    public val rows: List<TagTableRow> = buildRows()

    public fun source(id: Name): AcquisitionSourceSpec? = sourcesById[id]

    public fun source(id: String): AcquisitionSourceSpec? = source(id.asName())

    public fun timer(id: Name): AcquisitionTimerSpec? = timersById[id]

    public fun timer(id: String): AcquisitionTimerSpec? = timer(id.asName())

    public fun tag(id: Name): AcquisitionTagSpec? = tagsById[id]

    public fun tag(id: String): AcquisitionTagSpec? = tag(id.asName())

    public fun row(id: Name): TagTableRow? = rows.firstOrNull { it.tag.id == id }

    public fun row(id: String): TagTableRow? = row(id.asName())

    /** Delegates to [DataAcquisitionConfiguration.tagsForTimer], preserving timer order. */
    public fun tagsForTimer(timerId: Name): List<AcquisitionTagSpec> = configuration.tagsForTimer(timerId)

    public fun tagsForTimer(timerId: String): List<AcquisitionTagSpec> = tagsForTimer(timerId.asName())

    /** Binds this table to an acquisition reader. */
    public fun runner(
        reader: AcquisitionSourceReader,
        qualityPolicy: QualityPolicy = DefaultQualityPolicy,
    ): AcquisitionRunner = configuration.runner(reader, qualityPolicy)

    /**
     * Projects declared tags to a read-only device manifest. Execution and polling still come from
     * [AcquisitionRunner]; this manifest is a catalog/descriptor surface for clients.
     */
    public fun toManifest(
        id: Name,
        version: String = "0.1.0",
        propertyKind: PropertyKind = PropertyKind.MEASURED,
        features: Map<Name, PipelineFeatureSpec> = emptyMap(),
        meta: Meta = Meta.EMPTY,
        deviceContractFqName: String = "space.kscience.krig.assembly.TagTable",
    ): DeviceManifest = manifestOf(
        id = id,
        properties = tags.associate { tag ->
            tag.id to PropertyDescriptor(
                name = tag.id,
                kind = propertyKind,
                valueTypeId = tag.valueTypeId,
            )
        },
        version = version,
        features = features,
        meta = meta,
        deviceContractFqName = deviceContractFqName,
    )

    public fun toManifest(
        id: String,
        version: String = "0.1.0",
        propertyKind: PropertyKind = PropertyKind.MEASURED,
        features: Map<Name, PipelineFeatureSpec> = emptyMap(),
        meta: Meta = Meta.EMPTY,
        deviceContractFqName: String = "space.kscience.krig.assembly.TagTable",
    ): DeviceManifest = toManifest(id.asName(), version, propertyKind, features, meta, deviceContractFqName)

    private fun buildRows(): List<TagTableRow> {
        val timersByTagId = LinkedHashMap<Name, MutableList<AcquisitionTimerSpec>>()
        for (timer in timers) {
            for (tagId in timer.tags) {
                timersByTagId.getOrPut(tagId) { mutableListOf() }.add(timer)
            }
        }
        return tags.map { tag ->
            TagTableRow(
                tag = tag,
                source = sourcesById.getValue(tag.sourceId),
                timers = timersByTagId[tag.id].orEmpty().toList(),
            )
        }
    }
}

/** One rendered row of a [TagTable]. */
public data class TagTableRow(
    public val tag: AcquisitionTagSpec,
    public val source: AcquisitionSourceSpec,
    public val timers: List<AcquisitionTimerSpec>,
)

/** Builds a validated [TagTable] using the acquisition DSL. */
public fun tagTable(block: DataAcquisitionBuilder.() -> Unit): TagTable = dataAcquisition(block).asTagTable()

/** Validated tag-table facade over an existing acquisition configuration. */
public fun DataAcquisitionConfiguration.asTagTable(): TagTable = TagTable(this)

/** Stable in-memory key for demo/test tag samples. */
public data class TagTableAddress(
    public val sourceId: Name,
    public val address: String,
) {
    public constructor(sourceId: String, address: String) : this(sourceId.asName(), address)

    init {
        require(sourceId != Name.EMPTY) { "TagTableAddress sourceId must not be empty" }
        require(address.isNotBlank()) { "TagTableAddress address must not be blank" }
    }
}

public fun AcquisitionTagSpec.tagTableAddress(): TagTableAddress = TagTableAddress(sourceId, address)

/**
 * Mutable in-memory acquisition reader for demos, notebooks and tests.
 *
 * Samples are keyed by source id and connector-owned address. Missing samples are reported as
 * [OperationOutcome.Fail], so acquisition keeps the same quality/fault path as real connectors.
 */
public class InMemoryTagTableReader(
    initialSamples: Map<TagTableAddress, ObservedValue<Meta?>> = emptyMap(),
    private val clock: Clock = Clock.System,
    private val defaultQuality: DataQuality = DataQuality.GOOD,
) : AcquisitionSourceReader {
    private val samples: MutableMap<TagTableAddress, ObservedValue<Meta?>> = initialSamples.toMutableMap()

    public fun snapshot(): Map<TagTableAddress, ObservedValue<Meta?>> = samples.toMap()

    @IgnorableReturnValue
    public fun put(address: TagTableAddress, observed: ObservedValue<Meta?>): InMemoryTagTableReader {
        samples[address] = observed
        return this
    }

    @IgnorableReturnValue
    public fun put(
        sourceId: Name,
        address: String,
        value: Meta?,
        quality: DataQuality = defaultQuality,
    ): InMemoryTagTableReader = put(TagTableAddress(sourceId, address), ObservedValue(value, clock.now(), quality))

    @IgnorableReturnValue
    public fun put(
        sourceId: String,
        address: String,
        value: Meta?,
        quality: DataQuality = defaultQuality,
    ): InMemoryTagTableReader = put(sourceId.asName(), address, value, quality)

    public fun remove(address: TagTableAddress): Boolean = samples.remove(address) != null

    override suspend fun readSource(
        source: AcquisitionSourceSpec,
        tags: List<AcquisitionTagSpec>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> = tags.associate { tag ->
        tag.id to readTag(source, tag)
    }

    private fun readTag(
        source: AcquisitionSourceSpec,
        tag: AcquisitionTagSpec,
    ): OperationOutcome<ObservedValue<Meta?>> {
        if (tag.sourceId != source.id) {
            return fail(
                GenericOperationFault(
                    message = "Tag '${tag.id}' belongs to source '${tag.sourceId}', not '${source.id}'.",
                ),
            )
        }
        val address = tag.tagTableAddress()
        return samples[address]?.let { OperationOutcome.Ok(it) } ?: fail(
            GenericOperationFault(message = "No in-memory sample for tag '${tag.id}' at ${address.sourceId}:${address.address}."),
        )
    }
}
