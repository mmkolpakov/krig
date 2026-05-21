package space.kscience.krig.assembly

import kotlinx.serialization.SerializationException
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
            ).toTarget(deviceId = "pump", property = "rpm").let { }
            timer("fast", 50.milliseconds) { samples("rpm") }
        }

        assertTrue(config.validate().isEmpty())
        assertEquals("external.virtual", config.sources.single().connector)
        assertEquals("engine.rpm", config.tags.single().address)
        assertEquals("pump".asName(), config.tags.single().target?.deviceId)
        assertEquals(250, config.tags.single().timeoutMs)
    }

    @Test
    fun validateFlagsUnknownReferencesAndDuplicateTargets() {
        val config = DataAcquisitionConfiguration(
            sources = listOf(AcquisitionSourceSpec("s".asName(), "external")),
            timers = listOf(AcquisitionTimerSpec("t".asName(), 10, tags = listOf("missing".asName()))),
            tags = listOf(
                AcquisitionTagSpec("a".asName(), "s".asName(), "a", target = AcquisitionTargetSpec("d".asName(), "p".asName())),
                AcquisitionTagSpec("b".asName(), "ghost".asName(), "b", target = AcquisitionTargetSpec("d".asName(), "p".asName())),
            ),
        )

        val errors = config.validate()
        assertTrue(errors.any { "missing" in it }, "timer references should be validated: $errors")
        assertTrue(errors.any { "ghost" in it }, "source references should be validated: $errors")
        assertTrue(errors.any { "d.p" in it }, "duplicate targets should be validated: $errors")
    }

    @Test
    fun parseRejectsUnknownKeysByDefault() {
        assertFailsWith<SerializationException> {
            DataAcquisitionConfiguration.parse("""{"sources":[],"typo":true}""")
        }
    }
}
