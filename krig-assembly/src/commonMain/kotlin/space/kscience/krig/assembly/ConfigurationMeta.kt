package space.kscience.krig.assembly

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toJson
import space.kscience.dataforge.meta.toMeta

internal val StrictConfigurationJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    explicitNulls = false
}

internal val LenientConfigurationJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

internal fun <T> decodeConfigurationMeta(
    serializer: KSerializer<T>,
    meta: Meta,
    lenient: Boolean,
): T {
    val json = if (lenient) LenientConfigurationJson else StrictConfigurationJson
    return json.decodeFromJsonElement(serializer, meta.toJson().withoutMetaIndexMarkers())
}

internal fun <T> encodeConfigurationMeta(serializer: KSerializer<T>, value: T): Meta =
    StrictConfigurationJson.encodeToJsonElement(serializer, value).toMeta()

private fun JsonElement.withoutMetaIndexMarkers(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.withoutMetaIndexMarkers() })
    is JsonObject -> JsonObject(
        entries
            .asSequence()
            .filterNot { (key, _) -> key == Meta.INDEX_KEY }
            .associate { (key, value) -> key to value.withoutMetaIndexMarkers() },
    )
    else -> this
}
