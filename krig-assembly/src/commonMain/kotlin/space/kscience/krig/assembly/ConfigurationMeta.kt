package space.kscience.krig.assembly

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toJson
import space.kscience.krig.api.meta.serializableToMeta

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
    serializableToMeta(serializer, value, StrictConfigurationJson)

/** Outcome of decoding and validating a configuration document from [Meta]. */
public sealed interface ConfigurationLoadResult<out C> {
    public data class Valid<C>(public val config: C) : ConfigurationLoadResult<C>
    public data class Invalid(public val errors: List<String>) : ConfigurationLoadResult<Nothing>
}

/** Decodes [meta] with [serializer], then runs [validate]; never throws on malformed input. */
internal fun <C> loadConfigurationMeta(
    serializer: KSerializer<C>,
    meta: Meta,
    lenient: Boolean,
    validate: (C) -> List<String>,
): ConfigurationLoadResult<C> =
    runCatching { decodeConfigurationMeta(serializer, meta, lenient) }
        .fold(
            onSuccess = { config ->
                val errors = validate(config)
                if (errors.isEmpty()) ConfigurationLoadResult.Valid(config) else ConfigurationLoadResult.Invalid(errors)
            },
            onFailure = { error -> ConfigurationLoadResult.Invalid(listOf(error.message ?: error.toString())) },
        )

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
