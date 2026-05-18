package space.kscience.krig.assembly

import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.misc.DFBuilder
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

    /** Declares a device instance. Returns a handle for chaining with `from blueprint(...)`. */
    public fun source(id: String): SourceHandle = SourceHandle(id)

    /** Declares a wall-clock timer driving property sampling. */
    @Suppress("SameParameterValue")
    public fun timer(
        id: String,
        interval: Duration,
        block: TimerBuilder.() -> Unit = {},
    ) {
        val builder = TimerBuilder(id).apply(block)
        timers += TimerSpec(id = id, intervalMs = interval.inWholeMilliseconds, properties = builder.propertyIds)
    }

    /** Declares a property collector. Returns a handle for chaining. */
    public fun property(id: String): PropertyHandle = PropertyHandle(id)

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

    /** Fluent handle completed by `from blueprint(...)` or `with(config)`. */
    @DFBuilder
    public inner class SourceHandle internal constructor(private val id: String) {
        /** Binds [blueprintId] to this source. */
        public infix fun from(blueprintId: String): SourceSpec =
            this@DataPlatformBuilder.appendSource(SourceSpec(id = id, blueprintId = blueprintId))

        /** Attaches opaque [config]. Typically chained after `from`. */
        public infix fun SourceSpec.with(config: JsonObject): SourceSpec {
            val replacement = copy(config = config)
            this@DataPlatformBuilder.replaceSource(this, replacement)
            return replacement
        }
    }

    /** Fluent handle for a property collector. */
    @DFBuilder
    public inner class PropertyHandle internal constructor(private val id: String) {
        /**
         * Binds the source and property to this collector. Appends a [PropertySpec];
         * reduce defaults to `LastValue`.
         */
        public fun from(sourceId: String, property: String, reduce: String = "LastValue", bufferCapacity: Int = 1024): PropertySpec =
            this@DataPlatformBuilder.appendProperty(
                PropertySpec(
                    id = id,
                    sourceId = sourceId,
                    property = property,
                    reduce = reduce,
                    bufferCapacity = bufferCapacity,
                ),
            )
    }
}

/** Nested builder: timer → properties list. */
@DFBuilder
public class TimerBuilder internal constructor(public val id: String) {
    internal val propertyIds: MutableList<String> = mutableListOf()

    /** Registers property ids this timer drives. Matches [DataPlatformBuilder.property] ids. */
    public fun samples(vararg propertyIds: String) {
        this.propertyIds.addAll(propertyIds)
    }
}
