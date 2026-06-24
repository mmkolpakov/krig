package space.kscience.krig.api.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.toJson
import space.kscience.dataforge.meta.toMeta

private val defaultMetaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Encodes [obj] via [serializer] into a [Meta].
 *
 * The conversion goes through an intermediate `JsonElement` (`encodeToJsonElement` → `toMeta`) —
 * a known tax acceptable on configuration and fault paths; do not call on hot data paths.
 */
public fun <T> serializableToMeta(serializer: KSerializer<T>, obj: T, json: Json = defaultMetaJson): Meta {
    val jsonElement = json.encodeToJsonElement(serializer, obj)
    return jsonElement.toMeta()
}

/**
 * Bidirectional [MetaConverter] around an explicit [KSerializer]. Use when the serializer
 * is only known at the value site (generic DSL delegates); otherwise prefer DataForge's
 * reified factory.
 */
public fun <T> serializableMetaConverter(
    serializer: KSerializer<T>,
    json: Json = defaultMetaJson,
): MetaConverter<T> = object : MetaConverter<T> {
    // Only decoding failures map to null; programming errors inside custom serializers must surface.
    override fun readOrNull(source: Meta): T? = try {
        val jsonElement = source.toJson()
        json.decodeFromJsonElement(serializer, jsonElement)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun convert(obj: T): Meta {
        val jsonElement = json.encodeToJsonElement(serializer, obj)
        return jsonElement.toMeta()
    }
}
