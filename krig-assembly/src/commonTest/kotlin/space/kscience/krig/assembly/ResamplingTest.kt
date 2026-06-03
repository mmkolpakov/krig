package space.kscience.krig.assembly

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ResamplingTest {

    private val rpm = "rpm".asName()
    private val load = "load".asName()
    private val bad = DataQuality(QualitySeverity.BAD, detail = "sensor fault")

    private fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    /** rpm ramps 900→920 over 0..20ms; load ramps 0.30→0.32. Last load sample is BAD. */
    private fun rampChunk(): DenseDoubleTimeSeriesChunk = DenseDoubleTimeSeriesChunk(
        series = listOf(rpm, load),
        rows = listOf(
            DenseDoubleTimeSeriesRow(at(0), doubleArrayOf(900.0, 0.30)),
            DenseDoubleTimeSeriesRow(at(10), doubleArrayOf(910.0, 0.31)),
            DenseDoubleTimeSeriesRow(at(20), doubleArrayOf(920.0, 0.32), qualityOverrides = mapOf(1 to bad)),
        ),
    )

    @Test
    fun linearResampleMatchesAnalyticRamp() {
        val resampled = rampChunk().resampleOnto(listOf(at(5), at(15)))
        assertEquals(2, resampled.rowCount)
        assertEquals(905.0, resampled.value(0, 0), 1e-9)
        assertEquals(915.0, resampled.value(1, 0), 1e-9)
        assertEquals(0.305, resampled.value(0, 1), 1e-9)
    }

    @Test
    fun uniformResampleSpansObservedRangeInclusive() {
        val resampled = rampChunk().resample(5.milliseconds)
        assertEquals(5, resampled.rowCount)
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), (0 until resampled.rowCount).map { resampled.times[it].toEpochMilliseconds() })
    }

    @Test
    fun resampleDropsOutOfRangeGridPoints() {
        val resampled = rampChunk().resampleOnto(listOf(at(-5), at(5), at(25)))
        assertEquals(1, resampled.rowCount)
        assertEquals(5L, resampled.times.first().toEpochMilliseconds())
    }

    @Test
    fun splineRequiresThreeSourceRows() {
        val twoRow = DenseDoubleTimeSeriesChunk(
            series = listOf(rpm),
            rows = listOf(
                DenseDoubleTimeSeriesRow(at(0), doubleArrayOf(1.0)),
                DenseDoubleTimeSeriesRow(at(10), doubleArrayOf(2.0)),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            twoRow.resampleOnto(listOf(at(5)), ResamplingMethod.SPLINE)
        }
    }

    @Test
    fun resampledRowInheritsBracketingQuality() {
        val resampled = rampChunk().resampleOnto(listOf(at(15)))
        assertEquals(QualitySeverity.BAD, resampled.aggregateQualityAt(0).severity)
    }
}
