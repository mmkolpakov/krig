package space.kscience.krig.assembly

import kotlinx.serialization.SerializationException
import space.kscience.dataforge.io.JsonMetaFormat
import space.kscience.dataforge.io.parse
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DataPlatformConfigurationTest {

    @Test
    fun validateFlagsUnknownPropertyReferencedByTimer() {
        val config = DataPlatformConfiguration(
            sources = listOf(SourceSpec(id = "motor".asName(), manifestId = "bp.motor".asName())),
            timers = listOf(TimerSpec(id = "fast".asName(), intervalMs = 50, properties = listOf("missing".asName()))),
            properties = listOf(),
        )
        val errors = config.validate()
        assertTrue(errors.any { "missing" in it }, "timer referencing unknown property must be flagged: $errors")
    }

    @Test
    fun validateFlagsUnknownSourceIdOnProperty() {
        val config = DataPlatformConfiguration(
            sources = listOf(SourceSpec(id = "motor".asName(), manifestId = "bp.motor".asName())),
            timers = emptyList(),
            properties = listOf(
                PropertySpec(id = "motor.pv".asName(), sourceId = "ghost".asName(), property = "pv".asName()),
            ),
        )
        val errors = config.validate()
        assertTrue(errors.any { "ghost" in it }, "property.sourceId pointing nowhere must be flagged: $errors")
    }

    @Test
    fun validateAcceptsCoherentConfig() {
        val config = DataPlatformConfiguration(
            sources = listOf(SourceSpec(id = "motor".asName(), manifestId = "bp.motor".asName())),
            timers = listOf(TimerSpec(id = "fast".asName(), intervalMs = 50, properties = listOf("motor.pv".asName()))),
            properties = listOf(PropertySpec(id = "motor.pv".asName(), sourceId = "motor".asName(), property = "pv".asName())),
        )
        assertTrue(config.validate().isEmpty())
    }

    @Test
    fun timerSpecRejectsNonPositiveInterval() {
        assertFailsWith<IllegalArgumentException> {
            TimerSpec(id = "bad".asName(), intervalMs = 0)
        }
    }

    @Test
    fun propertySpecAcceptsAnyPositiveCapacity() {
        // Non-power-of-two capacity is accepted; rounding happens at materialization.
        val spec = PropertySpec(id = "p".asName(), sourceId = "s".asName(), property = "pv".asName(), bufferCapacity = 1000)
        assertEquals(1000, spec.bufferCapacity)
    }

    @Test
    fun dslProducesSameConfigAsDirectConstruction() {
        val built = dataPlatform {
            (source("motor") from "bp.motor").let { }
            property("motor.pv").from(sourceId = "motor", property = "pv", bufferCapacity = 2048).let { }
            timer("fast", 50.milliseconds) { samples("motor.pv") }
        }

        assertEquals(1, built.sources.size)
        assertEquals("bp.motor".asName(), built.sources.single().manifestId)
        assertEquals(2048, built.properties.single().bufferCapacity)
        assertEquals(50, built.timers.single().intervalMs)
        assertEquals(listOf("motor.pv".asName()), built.timers.single().properties)
    }

    @Test
    fun parseRoundTripsCoherentJson() {
        val json = """
            {
              "sources": [{"id": "motor", "manifestId": "bp.motor"}],
              "timers": [{"id": "fast", "intervalMs": 50, "properties": ["motor.pv"]}],
              "properties": [{"id": "motor.pv", "sourceId": "motor", "property": "pv"}]
            }
        """.trimIndent()
        val parsed = DataPlatformConfiguration.fromMeta(JsonMetaFormat().parse(json))
        assertTrue(parsed.validate().isEmpty())
        assertEquals("motor".asName(), parsed.sources.single().id)
    }

    @Test
    fun parseRejectsUnknownKeysByDefault() {
        val json = """
            {
              "sources": [],
              "bufferCapcity": 100
            }
        """.trimIndent()

        assertFailsWith<SerializationException> {
            DataPlatformConfiguration.fromMeta(JsonMetaFormat().parse(json))
        }
    }

    @Test
    fun parseLenientAcceptsUnknownKeysExplicitly() {
        val json = """
            {
              "sources": [],
              "bufferCapcity": 100
            }
        """.trimIndent()

        val parsed = DataPlatformConfiguration.fromMeta(JsonMetaFormat().parse(json), lenient = true)

        assertTrue(parsed.validate().isEmpty())
    }
}
