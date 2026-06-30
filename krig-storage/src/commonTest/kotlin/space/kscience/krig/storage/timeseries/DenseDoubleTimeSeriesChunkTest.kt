package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun denseIntChunkUsesPrimitiveColumnsAndQualityBand() {
        val chunk = DenseIntTimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                DenseIntTimeSeriesRow(Instant.fromEpochMilliseconds(0), intArrayOf(900, 30)),
                DenseIntTimeSeriesRow(
                    Instant.fromEpochMilliseconds(10),
                    intArrayOf(910, 31),
                    qualityOverrides = mapOf(0 to bad),
                ),
            ),
        )

        assertContentEquals(intArrayOf(900, 910), chunk.column(0))
        assertEquals(31, chunk.value(1, 1))
        assertContentEquals(intArrayOf(910, 31), chunk.row(1).values)
        assertEquals(QualitySeverity.BAD, chunk.qualityAt(1, 0).severity)
        assertEquals(QualitySeverity.GOOD, chunk.qualityAt(1, 1).severity)
        assertEquals(QualitySeverity.BAD, chunk.aggregateQualityAt(1).severity)
    }

    @Test
    fun sparsePrimitiveChunksProjectToDenseColumns() {
        val longChunk = TimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                TimeSeriesRow(Instant.fromEpochMilliseconds(0), mapOf(rpm to 1L)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(10), mapOf(rpm to 2L, load to 5L)),
            ),
        ).toDenseLongChunk(default = -1L)
        val booleanChunk = TimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                TimeSeriesRow(Instant.fromEpochMilliseconds(0), mapOf(rpm to true)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(10), mapOf(load to true)),
            ),
        ).toDenseBooleanChunk(default = false)

        assertContentEquals(longArrayOf(1L, 2L), longChunk.column(0))
        assertContentEquals(longArrayOf(-1L, 5L), longChunk.column(1))
        assertContentEquals(booleanArrayOf(true, false), booleanChunk.column(0))
        assertContentEquals(booleanArrayOf(false, true), booleanChunk.column(1))
    }
}
