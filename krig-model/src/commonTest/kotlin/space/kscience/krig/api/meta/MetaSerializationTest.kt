package space.kscience.krig.api.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.toJson
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaSerializationTest {
    @Serializable
    private data class Payload(
        val id: String,
        val value: Double,
    )

    @Test
    fun serializableMetaConverterRoundTripsDto() {
        val payload = Payload(id = "pump.rpm", value = 42.0)
        val converter = serializableMetaConverter(Payload.serializer())

        assertEquals(payload, converter.read(serializableToMeta(Payload.serializer(), payload)))
    }

    @Test
    fun serializableToMetaPreservesSerializableFields() {
        val meta = serializableToMeta(Payload.serializer(), Payload(id = "pump.rpm", value = 42.0))
        val json = meta.toJson()

        assertEquals("pump.rpm", json.jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(42.0, json.jsonObject["value"]?.jsonPrimitive?.double)
    }
}
