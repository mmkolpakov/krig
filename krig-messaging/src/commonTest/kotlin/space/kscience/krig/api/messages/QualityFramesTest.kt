package space.kscience.krig.api.messages

import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QualityFramesTest {

    @Test
    fun goodQualityKeepsLeanContext() {
        val context = MessageContext.Empty
        assertSame(context, context.withQuality(DataQuality.GOOD))
        assertNull(context.quality)
    }

    @Test
    fun degradedQualityRoundTripsThroughMeta() {
        val quality = DataQuality(
            severity = QualitySeverity.UNCERTAIN,
            code = QualityCode("opcua.stale"),
            detail = "last good 5s ago",
        )
        val context = MessageContext.Empty.withQuality(quality)
        assertEquals(quality, context.quality)
    }

    @Test
    fun qualityPreservesExistingAttributes() {
        val base = MessageContext.Empty.withQuality(
            DataQuality(QualitySeverity.BAD, detail = "sensor fault"),
        )
        val quality = base.quality
        assertEquals(QualitySeverity.BAD, quality?.severity)
        assertEquals("sensor fault", quality?.detail)
    }
}
