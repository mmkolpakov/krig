package space.kscience.krig.assembly

import space.kscience.dataforge.misc.DFBuilder
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MutableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.time.Duration

/** DSL entry-point: `dataPlatform { source(...); timer(...); property(...) }`. */
public fun dataPlatform(block: DataPlatformBuilder.() -> Unit): DataPlatformConfiguration =
    DataPlatformBuilder().apply(block).build()

/** Mutable builder for [DataPlatformConfiguration]. Not thread-safe; single-author use. */
@DFBuilder
public class DataPlatformBuilder internal constructor() {
    private val sources = mutableListOf<SourceSpec>()
    private val timers = mutableListOf<TimerSpec>()
    private val properties = mutableListOf<PropertySpec>()

    /** Declares a device instance. Returns a handle for chaining with `from manifest(...)`. */
    public fun source(id: Name): SourceHandle = SourceHandle(id)

    public fun source(id: String): SourceHandle = source(id.asName())

    /** Declares a timer driving property sampling. */
    public fun timer(
        id: Name,
        interval: Duration,
        block: TimerBuilder.() -> Unit = {},
    ) {
        val builder = TimerBuilder(id).apply(block)
        timers += TimerSpec(id = id, intervalMs = interval.inWholeMilliseconds, properties = builder.propertyIds)
    }

    public fun timer(
        id: String,
        interval: Duration,
        block: TimerBuilder.() -> Unit = {},
    ): Unit = timer(id.asName(), interval, block)

    /** Declares a property collector. Returns a handle for chaining. */
    public fun property(id: Name): PropertyHandle = PropertyHandle(id)

    public fun property(id: String): PropertyHandle = property(id.asName())

    internal fun build(): DataPlatformConfiguration =
        DataPlatformConfiguration(sources.toList(), timers.toList(), properties.toList())

    internal fun appendSource(spec: SourceSpec): SourceSpec {
        sources += spec
        return spec
    }

    internal fun replaceSource(old: SourceSpec, replacement: SourceSpec) {
        sources[sources.indexOf(old)] = replacement
    }

    internal fun appendProperty(spec: PropertySpec): PropertySpec {
        properties += spec
        return spec
    }

    /** Fluent handle completed by `from manifest(...)` or `with(config)`. */
    @DFBuilder
    public inner class SourceHandle internal constructor(private val id: Name) {
        /** Binds [manifestId] to this source. */
        @IgnorableReturnValue
        public infix fun from(manifestId: Name): SourceSpec =
            this@DataPlatformBuilder.appendSource(SourceSpec(id = id, manifestId = manifestId))

        @IgnorableReturnValue
        public infix fun from(manifestId: String): SourceSpec = from(manifestId.asName())

        /** Attaches opaque [config]. Typically chained after `from`. */
        @IgnorableReturnValue
        public infix fun SourceSpec.with(config: Meta): SourceSpec {
            val replacement = copy(config = config)
            this@DataPlatformBuilder.replaceSource(this, replacement)
            return replacement
        }

        /** Attaches opaque [Meta] config using a DataForge Meta builder. */
        @IgnorableReturnValue
        public fun SourceSpec.withMeta(block: MutableMeta.() -> Unit): SourceSpec =
            with(Meta(block))
    }

    /** Fluent handle for a property collector. */
    @DFBuilder
    public inner class PropertyHandle internal constructor(private val id: Name) {
        /**
         * Binds the source and property to this collector. Appends a [PropertySpec];
         * reduction defaults to [ReductionSpec.Last].
         */
        @IgnorableReturnValue
        public fun from(
            sourceId: Name,
            property: Name,
            reduction: ReductionSpec = ReductionSpec.Last,
            bufferCapacity: Int = 1024,
        ): PropertySpec =
            this@DataPlatformBuilder.appendProperty(
                PropertySpec(
                    id = id,
                    sourceId = sourceId,
                    property = property,
                    reduction = reduction,
                    bufferCapacity = bufferCapacity,
                ),
            )

        @IgnorableReturnValue
        public fun from(
            sourceId: String,
            property: String,
            reduction: ReductionSpec = ReductionSpec.Last,
            bufferCapacity: Int = 1024,
        ): PropertySpec = from(sourceId.asName(), property.asName(), reduction, bufferCapacity)
    }
}

/** Nested builder: timer → properties list. */
@DFBuilder
public class TimerBuilder internal constructor(public val id: Name) {
    internal val propertyIds: MutableList<Name> = mutableListOf()

    /** Registers property ids this timer drives. Matches [DataPlatformBuilder.property] ids. */
    public fun samples(vararg propertyIds: Name) {
        this.propertyIds.addAll(propertyIds)
    }

    public fun samples(vararg propertyIds: String) {
        this.propertyIds.addAll(propertyIds.map { it.asName() })
    }
}
