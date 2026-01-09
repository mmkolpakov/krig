package space.kscience.controls.common.time

import space.kscience.dataforge.meta.*
import kotlin.properties.ReadWriteProperty
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A strictly validating [MetaConverter] for [Instant].
 *
 * This prevents the system from silently falling back to defaults when a configuration error occurs.
 */
public object InstantMetaConverter : MetaConverter<Instant> {
    override fun readOrNull(source: Meta): Instant? {
        if (source.value == null) return null
        return source.string?.let { Instant.parse(it) }
            ?: error("Meta value '$source' cannot be interpreted as Instant string.")
    }

    override fun convert(obj: Instant): Meta = Meta(obj.toString())
}

/**
 * A strictly validating [MetaConverter] for [Duration].
 * - Supports ISO-8601 duration format (e.g., "PT1.5S").
 * - Throws on invalid format.
 */
public object DurationMetaConverter : MetaConverter<Duration> {
    override fun readOrNull(source: Meta): Duration? {
        if (source.value == null) return null

        return source.string?.let { Duration.parse(it) }
            ?: error("Meta value '$source' cannot be interpreted as Duration string.")
    }

    override fun convert(obj: Duration): Meta = Meta(obj.toString())
}

/**
 * Extension to safely parse an Instant from Meta.
 * Throws if the data exists but is malformed.
 */
public val Meta?.instant: Instant? get() = this?.let { InstantMetaConverter.readOrNull(it) }

/**
 * Extension to safely parse a Duration from Meta.
 * Throws if the data exists but is malformed.
 */
public val Meta?.duration: Duration? get() = this?.let { DurationMetaConverter.readOrNull(it) }

/**
 * Delegate for [Duration] properties in [Scheme]s.
 *
 * @param default The default duration to use ONLY if the key is missing.
 * @throws IllegalArgumentException if the key exists but contains an invalid duration string.
 */
public fun Scheme.duration(default: Duration): ReadWriteProperty<Any?, Duration> =
    convertable(DurationMetaConverter, default)

/**
 * Delegate for nullable [Duration] properties in [Scheme]s.
 *
 * @throws IllegalArgumentException if the key exists but contains an invalid duration string.
 */
public fun Scheme.duration(): ReadWriteProperty<Any?, Duration?> =
    convertable(DurationMetaConverter)