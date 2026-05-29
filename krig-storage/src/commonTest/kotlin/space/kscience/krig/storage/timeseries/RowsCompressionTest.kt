package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class RowsCompressionTest {
    private val pv = "pv".asName()

    @Test
    fun sparseCompressionDropsRepeatedRowsAndValues() {
        val chunk = TimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                TimeSeriesRow(Instant.fromEpochMilliseconds(0), mapOf(pv to 1.0)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(1), mapOf(pv to 1.0)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(2), mapOf(pv to 2.0)),
            ),
        )

        val compressed = chunk.compressRows()

        assertEquals(2, compressed.rows.size)
        assertEquals(1.0, compressed.rows[0].values.getValue(pv))
        assertEquals(2.0, compressed.rows[1].values.getValue(pv))
    }

    @Test
    fun sparseMinIntervalDoesNotLoseChangedValue() {
        val chunk = TimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                TimeSeriesRow(Instant.fromEpochMilliseconds(0), mapOf(pv to 1.0)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(5), mapOf(pv to 2.0)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(10), mapOf(pv to 2.0)),
            ),
        )

        val compressed = chunk.compressRows(RowsCompression(minIntervalMillis = 10))

        assertEquals(listOf(0L, 10L), compressed.rows.map { it.time.toEpochMilliseconds() })
        assertEquals(2.0, compressed.rows[1].values.getValue(pv))
    }

    @Test
    fun sparseMinIntervalDoesNotLoseQualityChange() {
        val bad = DataQuality(QualitySeverity.BAD)
        val chunk = TimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                TimeSeriesRow(Instant.fromEpochMilliseconds(0), mapOf(pv to 1.0)),
                TimeSeriesRow(Instant.fromEpochMilliseconds(5), mapOf(pv to 1.0), bad),
                TimeSeriesRow(Instant.fromEpochMilliseconds(10), mapOf(pv to 1.0), bad),
            ),
        )

        val compressed = chunk.compressRows(RowsCompression(minIntervalMillis = 10))

        assertEquals(listOf(0L, 10L), compressed.rows.map { it.time.toEpochMilliseconds() })
        assertEquals(bad, compressed.rows[1].quality)
    }

    @Test
    fun denseCompressionRespectsNumericDeltaAndQualityChanges() {
        val uncertain = DataQuality(QualitySeverity.UNCERTAIN)
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(1.0)),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(1), doubleArrayOf(1.05)),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(2), doubleArrayOf(1.08), uncertain),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(3), doubleArrayOf(1.30), uncertain),
            ),
        )

        val compressed = chunk.compressRows(RowsCompression(numericDelta = 0.1))

        assertEquals(3, compressed.rows.size)
        assertEquals(listOf(0L, 2L, 3L), compressed.rows.map { it.time.toEpochMilliseconds() })
        assertTrue(compressed.rows[1].aggregateQuality.severity >= QualitySeverity.UNCERTAIN)
    }

    @Test
    fun denseMinIntervalDoesNotLoseChangedValue() {
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(1.0)),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(5), doubleArrayOf(2.0)),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(10), doubleArrayOf(2.0)),
            ),
        )

        val compressed = chunk.compressRows(RowsCompression(minIntervalMillis = 10))

        assertEquals(listOf(0L, 10L), compressed.rows.map { it.time.toEpochMilliseconds() })
        assertEquals(2.0, compressed.rows[1].values.single())
    }
}
