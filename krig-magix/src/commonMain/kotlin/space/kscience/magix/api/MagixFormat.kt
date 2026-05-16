package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.names.Name

/**
 * A type-safe wrapper that associates a payload type [T] with its [KSerializer] and a set of
 * format identifiers. This enables automatic, polymorphic deserialization of `MagixMessage` payloads.
 *
 * @param T The type of the payload.
 * @property serializer The `kotlinx.serialization` serializer for type [T].
 * @property formats A set of format strings that identify this payload type. The first format in the set
 *                   is considered the primary or default format for sending.
 */
public data class MagixFormat<T>(
    val serializer: KSerializer<T>,
    val formats: Set<String>,
) {
    init {
        require(formats.isNotEmpty()) { "MagixFormat must have at least one format identifier." }
    }

    /**
     * The default format identifier to be used when sending messages of this type.
     */
    public val defaultFormat: String get() = formats.first()
}

/**
 * Subscribes to messages from a [MagixEndpoint] and automatically decodes their payloads
 * using the provided [MagixFormat].
 *
 * @param T The expected type of the payload.
 * @param format The [MagixFormat] used for filtering messages by format and for deserializing payloads.
 * @param json The [Json] instance for serialization, typically assembled by `SerializationPlugin`.
 * @param originFilter An optional list of source endpoint IDs to subscribe to.
 * @param targetFilter An optional list of target endpoint IDs to subscribe to.
 * @param topicPattern An optional topic pattern for more granular filtering.
 * @return A [Flow] of pairs, containing the raw [MagixMessage] and the deserialized payload of type [T].
 */
public fun <T> MagixEndpoint.subscribe(
    format: MagixFormat<T>,
    json: Json,
    originFilter: Collection<Name>? = null,
    targetFilter: Collection<Name?>? = null,
    topicPattern: Name? = null,
): Flow<Pair<MagixMessage, T>> = subscribe(
    MagixMessageFilter(format = format.formats, source = originFilter, target = targetFilter, topicPattern = topicPattern)
).map { message ->
    val value: T = json.decodeFromJsonElement(format.serializer, message.payload)
    message to value
}

/**
 * Broadcasts a message with a typed payload, automatically serializing it using the provided [MagixFormat].
 *
 * @param T The type of the payload.
 * @param format The [MagixFormat] to use for serialization and to set the `format` field in the message header.
 * @param json The [Json] instance for serialization, typically assembled by `SerializationPlugin`.
 * @param payload The payload object of type [T] to be sent.
 * @param source The source endpoint ID for the outgoing message.
 * @param target An optional target endpoint ID. If null, the message is a broadcast.
 * @param topic An optional topic for this specific message.
 * @param id An optional unique ID for the message.
 * @param parentId An optional ID of a parent message this message is responding to or related to.
 * @param user Optional user information as a [JsonElement].
 * @param headers Optional structured message headers for broker-level metadata.
 */
public suspend fun <T> MagixEndpoint.send(
    format: MagixFormat<T>,
    json: Json,
    payload: T,
    source: Name,
    target: Name? = null,
    topic: Name? = null,
    id: String? = null,
    parentId: String? = null,
    user: JsonElement? = null,
    headers: JsonObject = JsonObject(emptyMap()),
) {
    val message = MagixMessage(
        format = format.defaultFormat,
        payload = json.encodeToJsonElement(format.serializer, payload),
        sourceEndpoint = source,
        targetEndpoint = target,
        topic = topic,
        id = id,
        parentId = parentId,
        user = user,
        headers = headers,
    )
    broadcast(message)
}
