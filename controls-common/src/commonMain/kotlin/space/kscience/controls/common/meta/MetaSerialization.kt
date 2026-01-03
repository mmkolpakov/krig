package space.kscience.controls.common.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.toJson
import space.kscience.dataforge.meta.toMeta

public val baseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Converts a `@Serializable` object to a [Meta] representation using the core JSON config.
 */
public fun <T> serializableToMeta(serializer: KSerializer<T>, obj: T, json: Json = baseJson): Meta {
    val jsonElement = json.encodeToJsonElement(serializer, obj)
    return jsonElement.toMeta()
}

/**
 * A generic factory for [MetaConverter] that leverages `kotlinx.serialization` for any `@Serializable` class.
 *
 * @param T The serializable type to be converted.
 * @param serializer The explicit KSerializer for the type T.
 * @return A [MetaConverter] instance for the given type.
 */
public fun <T> serializableMetaConverter(serializer: KSerializer<T>, json: Json = baseJson): MetaConverter<T> = object : MetaConverter<T> {
    /**
     * Converts a [Meta] object back to a typed object [T].
     * This process involves an intermediate conversion to [kotlinx.serialization.json.JsonElement].
     *
     * @param source The [Meta] to be read.
     * @return The deserialized object of type [T], or null if the conversion fails.
     */
    override fun readOrNull(source: Meta): T? {
        return try {
            val jsonElement = source.toJson()
            json.decodeFromJsonElement(serializer, jsonElement)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a typed object [T] to its [Meta] representation.
     *
     * @param obj The object to be converted.
     * @return The [Meta] representation.
     */
    override fun convert(obj: T): Meta {
        val jsonElement = json.encodeToJsonElement(serializer, obj)
        return jsonElement.toMeta()
    }
}

/**
 * Creates a [MetaConverter] for a `@Serializable` type [T] by inferring its serializer.
 * This is a convenience extension for simple, non-generic serializable classes.
 *
 * For complex generic types (like `List<T>` or `Map<K, V>`), use the overload that accepts
 * an explicit `KSerializer`.
 *
 * @see serializable
 */
public inline fun <reified T> MetaConverter.Companion.serializable(): MetaConverter<T> =
    serializableMetaConverter(serializer<T>())

/**
 * Creates a [MetaConverter] for any type [T] using an explicit [KSerializer].
 * This is the correct way to handle complex generic types that require manually
 * constructed serializers.
 *
 * Example usage:
 * ```
 * val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
 * val mapConverter = MetaConverter.serializable(mapSerializer)
 * ```
 */
public fun <T> MetaConverter.Companion.serializable(serializer: KSerializer<T>): MetaConverter<T> =
    serializableMetaConverter(serializer)