package space.kscience.krig.api.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataQualityTest {

    @Test
    fun severityOrderingIsOpen() {
        val vendorWarning = QualitySeverity(75)
        val critical = QualitySeverity(200)

        assertTrue(QualitySeverity.GOOD < QualitySeverity.UNCERTAIN)
        assertTrue(QualitySeverity.UNCERTAIN < vendorWarning)
        assertTrue(vendorWarning < QualitySeverity.BAD)
        assertTrue(QualitySeverity.BAD < critical)
    }

    @Test
    fun combineKeepsWorseQuality() {
        val good = DataQuality.GOOD
        val uncertain = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("test.stale"))
        val bad = DataQuality(QualitySeverity.BAD, QualityCode("test.timeout"))

        assertEquals(uncertain, good.combine(uncertain))
        assertEquals(uncertain, uncertain.combine(good))
        assertEquals(bad, uncertain.combine(bad))
        assertEquals(bad, bad.combine(uncertain))
    }

    @Test
    fun combineKeepsLeftQualityOnTie() {
        val first = DataQuality(QualitySeverity.BAD, QualityCode("test.timeout"))
        val second = DataQuality(QualitySeverity.BAD, QualityCode("test.communication"))

        assertEquals(first, first.combine(second))
        assertEquals(second, second.combine(first))
    }

    @Test
    fun combineAllDefaultsToGood() {
        assertSame(DataQuality.GOOD, emptyList<DataQuality>().combineAll())
        assertSame(DataQuality.GOOD, listOf(DataQuality.GOOD, DataQuality.GOOD).combineAll())
    }

    @Test
    fun combineAllPicksWorstQuality() {
        val uncertain = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("test.stale"))
        val vendorCritical = DataQuality(QualitySeverity(500), QualityCode("test.critical"))

        assertEquals(vendorCritical, listOf(DataQuality.GOOD, uncertain, vendorCritical).combineAll())
    }

    @Test
    fun qualityCodeRejectsBlankIds() {
        assertFailsWith<IllegalArgumentException> {
            QualityCode(" ")
        }
    }

    @Test
    fun observedValueUsabilityRequiresGoodQualityAndPresentValue() {
        val good = ObservedValue(42.0, Instant.fromEpochMilliseconds(1), DataQuality.GOOD)
        val stale = ObservedValue(42.0, Instant.fromEpochMilliseconds(2), DataQuality(QualitySeverity.UNCERTAIN))
        val missing = ObservedValue<Double?>(null, Instant.fromEpochMilliseconds(3), DataQuality.GOOD)

        assertTrue(good.isGood)
        assertTrue(good.isUsable)
        assertEquals(42.0, good.usableValue)
        assertEquals(42.0, good.requireUsableValue())

        assertFalse(stale.isUsable)
        assertNull(stale.usableValue)
        assertFailsWith<IllegalStateException> { stale.requireUsableValue() }

        assertFalse(missing.isUsable)
        assertNull(missing.usableValue)
    }
}
