package space.kscience.krig.assembly

import kotlinx.serialization.SerializationException
import space.kscience.dataforge.io.JsonMetaFormat
import space.kscience.dataforge.io.parse
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
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
        assertEquals(BatchTimeoutPolicy.SlowestTag, config.sources.single().batchTimeoutPolicy)
        assertEquals(AcquisitionCircuitBreakerPolicy.Disabled, config.sources.single().circuitBreaker)
        assertEquals("engine.rpm", config.tags.single().address)
        assertEquals(250, config.tags.single().timeoutMs)
    }

    @Test
    fun dslConfiguresSourceBatchTimeoutPolicy() {
        val config = dataAcquisition {
            source(
                id = "stand",
                connector = "external.virtual",
                batchTimeoutPolicy = BatchTimeoutPolicy.TightestTag,
            )
        }

        assertEquals(BatchTimeoutPolicy.TightestTag, config.sources.single().batchTimeoutPolicy)
    }

    @Test
    fun dslConfiguresSourceCircuitBreakerPolicy() {
        val policy = AcquisitionCircuitBreakerPolicy(failureThreshold = 3, resetTimeoutMs = 5_000)
        val config = dataAcquisition {
            source(
                id = "stand",
                connector = "external.virtual",
                circuitBreaker = policy,
            )
        }

        assertEquals(policy, config.sources.single().circuitBreaker)
    }

    @Test
    fun topologySourceKeepsAliasSeparateFromTopologyPath() {
        val config = dataAcquisition {
            topologySource(id = "lineA", topologyPath = "plant.line.a".parseAsName())
            tag("rpm").from("lineA", "drive.rpm")
        }

        val source = config.sources.single()
        assertEquals("lineA".asName(), source.id)
        assertEquals("plant.line.a".parseAsName(), source.topologyPath)
        assertEquals("lineA".asName(), config.tags.single().sourceId)
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
