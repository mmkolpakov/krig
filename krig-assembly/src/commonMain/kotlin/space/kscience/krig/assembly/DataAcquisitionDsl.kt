package space.kscience.krig.assembly

import space.kscience.dataforge.misc.DFBuilder
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MutableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
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
    @IgnorableReturnValue
    public fun source(
        id: Name,
        connector: Name,
        config: Meta = Meta.EMPTY,
    ): AcquisitionSourceSpec = AcquisitionSourceSpec(id, connector, config).also(sources::add)

    @IgnorableReturnValue
    public fun source(
        id: String,
        connector: String,
        config: Meta = Meta.EMPTY,
    ): AcquisitionSourceSpec = source(id.asName(), connector.asName(), config)

    @IgnorableReturnValue
    public fun source(
        id: Name,
        connector: String,
        config: Meta = Meta.EMPTY,
    ): AcquisitionSourceSpec = source(id, connector.asName(), config)

    @IgnorableReturnValue
    public fun source(
        id: Name,
        connector: Name,
        block: MutableMeta.() -> Unit,
    ): AcquisitionSourceSpec = source(id, connector, Meta(block))

    @IgnorableReturnValue
    public fun source(
        id: String,
        connector: String,
        block: MutableMeta.() -> Unit,
    ): AcquisitionSourceSpec = source(id.asName(), connector.asName(), Meta(block))

    @IgnorableReturnValue
    public fun source(
        id: Name,
        connector: String,
        block: MutableMeta.() -> Unit,
    ): AcquisitionSourceSpec = source(id, connector.asName(), Meta(block))

    /** Declares a timer and the tag ids sampled by that timer. */
    public fun timer(
        id: Name,
        interval: Duration,
        block: AcquisitionTimerBuilder.() -> Unit = {},
    ) {
        val builder = AcquisitionTimerBuilder(id).apply(block)
        timers += AcquisitionTimerSpec(id, interval.inWholeMilliseconds, builder.tagIds)
    }

    public fun timer(
        id: String,
        interval: Duration,
        block: AcquisitionTimerBuilder.() -> Unit = {},
    ): Unit = timer(id.asName(), interval, block)

    /** Starts a tag mapping. Complete it with `from(...).to(...)` when mapping into a device property. */
    public fun tag(id: Name): AcquisitionTagHandle = AcquisitionTagHandle(id)

    public fun tag(id: String): AcquisitionTagHandle = tag(id.asName())

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
    public inner class AcquisitionTagHandle internal constructor(private val id: Name) {
        @IgnorableReturnValue
        public fun from(
            sourceId: Name,
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

        @IgnorableReturnValue
        public fun from(
            sourceId: String,
            address: String,
            valueTypeId: String = space.kscience.krig.api.descriptors.TypeIds.META,
            timeout: Duration? = null,
            bufferCapacity: Int = 1024,
        ): AcquisitionTagBinding = from(sourceId.asName(), address, valueTypeId, timeout, bufferCapacity)
    }

    /** Fluent continuation returned by [AcquisitionTagHandle.from]. */
    @DFBuilder
    public inner class AcquisitionTagBinding internal constructor(
        private var current: AcquisitionTagSpec,
    ) {
        public val spec: AcquisitionTagSpec get() = current

        @IgnorableReturnValue
        public fun toTarget(deviceId: Name, property: Name): AcquisitionTagSpec {
            val replacement = current.copy(target = AcquisitionTargetSpec(deviceId, property))
            this@DataAcquisitionBuilder.replaceTag(current, replacement)
            current = replacement
            return replacement
        }

        @IgnorableReturnValue
        public fun toTarget(deviceId: String, property: String): AcquisitionTagSpec =
            toTarget(deviceId.asName(), property.asName())

        @IgnorableReturnValue
        public fun withoutTarget(): AcquisitionTagSpec = current
    }
}

/** Nested builder: timer -> tag ids. */
@DFBuilder
public class AcquisitionTimerBuilder internal constructor(public val id: Name) {
    internal val tagIds: MutableList<Name> = mutableListOf()

    /** Registers tag ids this timer samples. */
    public fun samples(vararg tagIds: Name) {
        this.tagIds.addAll(tagIds)
    }

    public fun samples(vararg tagIds: String) {
        this.tagIds.addAll(tagIds.map { it.asName() })
    }
}
