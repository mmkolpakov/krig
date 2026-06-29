package space.kscience.krig.assembly

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.metaOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class TagTableTest {

    @Test
    fun tagTableIndexesAcquisitionConfiguration() {
        val table = tagTable {
            source("stand", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("stand", "engine.rpm", TypeIds.DOUBLE)
            tag("temperature").from("stand", "engine.temperature", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm", "temperature") }
        }

        val rpm = assertNotNull(table.row("rpm"))

        assertEquals("stand".asName(), rpm.source.id)
        assertEquals(listOf("fast".asName()), rpm.timers.map { it.id })
        assertEquals(listOf("rpm".asName(), "temperature".asName()), table.tagsForTimer("fast").map { it.id })
        assertEquals(table.configuration.tags.single { it.id == "rpm".asName() }, table.tag("rpm"))
    }

    @Test
    fun tagTableManifestUsesDeclaredTagDescriptors() {
        val table = tagTable {
            source("stand", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("stand", "engine.rpm", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm") }
        }

        val manifest = table.toManifest("lab.tags")
        val rpm = manifest.properties.getValue("rpm".asName())

        assertEquals(PropertyKind.MEASURED, rpm.kind)
        assertEquals(TypeIds.DOUBLE, rpm.valueTypeId)
        assertEquals(emptyMap(), manifest.actions)
    }

    @Test
    fun inMemoryReaderPreservesObservedQuality() = runTest {
        val source = AcquisitionSourceSpec("stand".asName(), AcquisitionConnectors.KrigDevice)
        val tag = AcquisitionTagSpec("rpm".asName(), source.id, "engine.rpm", TypeIds.DOUBLE)
        val quality = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("lab.cached"))
        val time = Clock.System.now()
        val reader = InMemoryTagTableReader().put(
            TagTableAddress(source.id, tag.address),
            ObservedValue(metaOf(1_500.0), time, quality),
        )

        val observed = reader.readSource(source, listOf(tag)).getValue(tag.id).getOrThrow()

        assertEquals(1_500.0, MetaConverter.double.read(observed.value!!))
        assertEquals(time, observed.time)
        assertEquals(quality, observed.quality)
    }

    @Test
    fun inMemoryReaderReportsMissingSamplesAsFailures() = runTest {
        val source = AcquisitionSourceSpec("stand".asName(), AcquisitionConnectors.KrigDevice)
        val tag = AcquisitionTagSpec("rpm".asName(), source.id, "engine.rpm", TypeIds.DOUBLE)

        val outcome = InMemoryTagTableReader().readSource(source, listOf(tag)).getValue(tag.id)

        assertIs<OperationOutcome.Fail>(outcome)
    }
}
