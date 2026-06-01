package space.kscience.krig.api.faults

import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toJson
import kotlin.test.Test
import kotlin.test.assertEquals

class OperationFaultMetaSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun transportFaultMetaRoundTripsThroughSharedSerializer() {
        val fault = TransportFault(
            causeType = "IOException",
            message = "wire down",
            details = Meta { "port" put "COM1" },
        )

        val decoded = json.decodeFromJsonElement(TransportFault.serializer(), fault.toMeta().toJson())

        assertEquals(fault, decoded)
    }

    @Test
    fun validationFaultMetaRoundTripsThroughSharedSerializer() {
        val fault = ValidationFault(
            details = Meta { "property" put "rpm" },
            message = "Invalid rpm",
        )

        val decoded = json.decodeFromJsonElement(ValidationFault.serializer(), fault.toMeta().toJson())

        assertEquals(fault, decoded)
    }
}
