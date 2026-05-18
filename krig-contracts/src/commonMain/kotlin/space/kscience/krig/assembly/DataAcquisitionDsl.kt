package space.kscience.krig.assembly

import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.misc.DFBuilder
import kotlin.time.Duration

/** DSL entry-point: `dataAcquisition { source(...); tag(...); timer(...) }`. */
public fun dataAcquisition(block: DataAcquisitionBuilder.() -> Unit): DataAcquisitionConfiguration =
    DataAcquisitionBuilder().apply(block).build()

/** Mutable builder for [DataAcquisitionConfiguration]. Not thread-safe; single-author use. */
@DFBuilder
public class DataAcquisitionBuilder internal constructor() {
    private val sources = mutableListOf<AcquisitionSourceSpec>()
    private val timers = mutableListOf<AcquisitionTimerSpec>()
    private val tags = mutableListOf<AcquisitionTagSpec>()

    /** Declares an external acquisition source. [connector] is resolved outside krig. */
    public fun source(
        id: String,
        connector: String,
        config: JsonObject = JsonObject(emptyMap()),
    ): AcquisitionSourceSpec = AcquisitionSourceSpec(id, connector, config).also(sources::add)

    /** Declares a timer and the tag ids sampled by that timer. */
    @Suppress("SameParameterValue")
    public fun timer(
        id: String,
        interval: Duration,
        block: AcquisitionTimerBuilder.() -> Unit = {},
    ) {
        val builder = AcquisitionTimerBuilder(id).apply(block)
        timers += AcquisitionTimerSpec(id, interval.inWholeMilliseconds, builder.tagIds)
    }

    /** Starts a tag mapping. Complete it with `from(...).to(...)` when mapping into a device property. */
    public fun tag(id: String): AcquisitionTagHandle = AcquisitionTagHandle(id)

    internal fun build(): DataAcquisitionConfiguration =
        DataAcquisitionConfiguration(sources.toList(), timers.toList(), tags.toList())

    internal fun appendTag(spec: AcquisitionTagSpec): AcquisitionTagSpec {
        tags += spec
        return spec
    }

    internal fun replaceTag(old: AcquisitionTagSpec, replacement: AcquisitionTagSpec) {
        tags[tags.indexOf(old)] = replacement
    }

    @DFBuilder
    public inner class AcquisitionTagHandle internal constructor(private val id: String) {
        @Suppress("SameParameterValue")
        public fun from(
            sourceId: String,
            address: String,
            valueTypeId: String = space.kscience.krig.api.descriptors.TypeIds.META,
            timeout: Duration? = null,
            bufferCapacity: Int = 1024,
        ): AcquisitionTagBinding = this@DataAcquisitionBuilder.AcquisitionTagBinding(
            this@DataAcquisitionBuilder.appendTag(
                AcquisitionTagSpec(
                    id = id,
                    sourceId = sourceId,
                    address = address,
                    valueTypeId = valueTypeId,
                    timeoutMs = timeout?.inWholeMilliseconds,
                    bufferCapacity = bufferCapacity,
                ),
            ),
        )
    }

    /** Fluent continuation returned by [AcquisitionTagHandle.from]. */
    @DFBuilder
    public inner class AcquisitionTagBinding internal constructor(
        private var current: AcquisitionTagSpec,
    ) {
        public val spec: AcquisitionTagSpec get() = current

        public fun toTarget(deviceId: String, property: String): AcquisitionTagSpec {
            val replacement = current.copy(target = AcquisitionTargetSpec(deviceId, property))
            this@DataAcquisitionBuilder.replaceTag(current, replacement)
            current = replacement
            return replacement
        }

        public fun withoutTarget(): AcquisitionTagSpec = current
    }
}

/** Nested builder: timer -> tag ids. */
@DFBuilder
public class AcquisitionTimerBuilder internal constructor(public val id: String) {
    internal val tagIds: MutableList<String> = mutableListOf()

    /** Registers tag ids this timer samples. */
    public fun samples(vararg tagIds: String) {
        this.tagIds.addAll(tagIds)
    }
}
