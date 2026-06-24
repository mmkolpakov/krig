package space.kscience.krig.assembly

import kotlinx.serialization.SerializationException
import space.kscience.dataforge.io.JsonMetaFormat
import space.kscience.dataforge.io.parse
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.TypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DataAcquisitionConfigurationTest {
    @Test
    fun dslBuildsProtocolNeutralTagPlan() {
        val config = dataAcquisition {
            source("stand", connector = "external.virtual").let { }
            tag("rpm").from(
                sourceId = "stand",
                address = "engine.rpm",
                valueTypeId = TypeIds.DOUBLE,
                timeout = 250.milliseconds,
            ).let { }
            timer("fast", 50.milliseconds) { samples("rpm") }
        }

        assertTrue(config.validate().isEmpty())
        assertEquals("external.virtual".asName(), config.sources.single().connector)
        assertEquals("engine.rpm", config.tags.single().address)
        assertEquals(250, config.tags.single().timeoutMs)
    }

    @Test
    fun validateFlagsUnknownReferences() {
        val config = DataAcquisitionConfiguration(
            sources = listOf(AcquisitionSourceSpec("s".asName(), "external")),
            timers = listOf(AcquisitionTimerSpec("t".asName(), 10, tags = listOf("missing".asName()))),
            tags = listOf(
                AcquisitionTagSpec("a".asName(), "s".asName(), "a"),
                AcquisitionTagSpec("b".asName(), "ghost".asName(), "b"),
            ),
        )

        val errors = config.validate()
        assertTrue(errors.any { "missing" in it }, "timer references should be validated: $errors")
        assertTrue(errors.any { "ghost" in it }, "source references should be validated: $errors")
    }

    @Test
    fun parseRejectsUnknownKeysByDefault() {
        assertFailsWith<SerializationException> {
            DataAcquisitionConfiguration.fromMeta(JsonMetaFormat().parse("""{"sources":[],"typo":true}"""))
        }
    }
}
