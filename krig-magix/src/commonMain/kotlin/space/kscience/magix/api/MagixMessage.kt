package space.kscience.magix.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.names.Name

/**
 * Serializable KRig/DataForge Magix message.
 *
 * `sourceEndpoint` / `targetEndpoint` / `topic` are typed [Name] in Kotlin but serialise as
 * plain strings via dataforge's [NameSerializer]. Canonical Waltz RFC1 uses `origin` and
 * `target`; use [WaltzRfc1Message] adapters at that boundary.
 *
 * @property topic Hierarchical routing key. Strict external relays that ignore unknown keys
 *                 may drop it. Prefer [MagixEnvelope] or [toWaltzRfc1] for interop traffic.
 * @property headers Broker-level metadata (HLC, trace IDs). Same caveat as [topic].
 */
@Serializable
public data class MagixMessage(
    val format: String,
    val payload: JsonElement,
    val sourceEndpoint: Name,
    val targetEndpoint: Name? = null,
    val topic: Name? = null,
    val id: String? = null,
    val parentId: String? = null,
    val user: JsonElement? = null,
    val headers: JsonObject = JsonObject(emptyMap()),
)

/** Reads an optional structured header by [name]. */
public fun MagixMessage.header(name: String): JsonElement? = headers[name]
