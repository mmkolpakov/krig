package space.kscience.magix.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.NameSerializer

/**
 * Serializable Magix message, RFC1-compatible on the wire.
 *
 * `sourceEndpoint` / `targetEndpoint` / `topic` are typed [Name] in Kotlin but serialise as
 * plain strings via dataforge's [NameSerializer].
 *
 * @property topic Hierarchical routing key. Not part of RFC1; upstream relays that
 *                 `ignoreUnknownKeys` drop it. Prefer [MagixEnvelope] for interop traffic.
 * @property headers Broker-level metadata (HLC, trace IDs). Same caveat as [topic].
 */
@Serializable
public data class MagixMessage(
    val format: String,
    val payload: JsonElement,
    @Serializable(with = NameSerializer::class)
    val sourceEndpoint: Name,
    @Serializable(with = NameSerializer::class)
    val targetEndpoint: Name? = null,
    @Serializable(with = NameSerializer::class)
    val topic: Name? = null,
    val id: String? = null,
    val parentId: String? = null,
    val user: JsonElement? = null,
    val headers: JsonObject = JsonObject(emptyMap()),
)

/** Reads an optional structured header by [name]. */
public fun MagixMessage.header(name: String): JsonElement? = headers[name]
