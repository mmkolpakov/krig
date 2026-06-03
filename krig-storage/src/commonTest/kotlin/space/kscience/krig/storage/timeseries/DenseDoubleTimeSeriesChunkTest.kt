package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DenseDoubleTimeSeriesChunkTest {

    private val rpm = "rpm".asName()
    private val load = "load".asName()
    private val bad = DataQuality(QualitySeverity.BAD, detail = "sensor fault")

    private fun chunk(): DenseDoubleTimeSeriesChunk = DenseDoubleTimeSeriesChunk(
        series = listOf(rpm, load),
        rows = listOf(
            DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(900.0, 0.3)),
            DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(10), doubleArrayOf(910.0, 0.31)),
            DenseDoubleTimeSeriesRow(
                Instant.fromEpochMilliseconds(20),
                doubleArrayOf(920.0, 0.32),
                qualityOverrides = mapOf(1 to bad),
            ),
        ),
    )

    @Test
    fun columnAndValueAgreeAfterTranspose() {
        val chunk = chunk()
        assertEquals(3, chunk.rowCount)
        for (seriesIndex in chunk.series.indices) {
            val column = chunk.column(seriesIndex)
            assertEquals(chunk.rowCount, column.size)
            for (row in 0 until chunk.rowCount) {
                assertEquals(chunk.value(row, seriesIndex), column[row], "column/value disagree at [$row,$seriesIndex]")
            }
        }
    }

    @Test
    fun rowViewMatchesColumnStore() {
        val chunk = chunk()
        chunk.rows.forEachIndexed { row, dense ->
            for (seriesIndex in chunk.series.indices) {
                assertEquals(chunk.value(row, seriesIndex), dense.values[seriesIndex])
                assertEquals(chunk.value(row, seriesIndex), dense.valuesBuffer[seriesIndex])
            }
        }
    }

    @Test
    fun qualityOverrideIsPerColumnAndAggregates() {
        val chunk = chunk()
        assertEquals(QualitySeverity.GOOD, chunk.qualityAt(2, 0).severity)
        assertEquals(QualitySeverity.BAD, chunk.qualityAt(2, 1).severity)
        assertEquals(QualitySeverity.BAD, chunk.aggregateQualityAt(2).severity)
        assertEquals(QualitySeverity.GOOD, chunk.aggregateQualityAt(0).severity)
    }
}
