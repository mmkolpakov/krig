package space.kscience.krig.assembly

import space.kscience.dataforge.names.asName
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ReductionExecutorTest {

    private val pv = "pv".asName()

    private fun row(ms: Long, value: Double): DenseDoubleTimeSeriesRow =
        DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(ms), doubleArrayOf(value))

    private fun chunk(): DenseDoubleTimeSeriesChunk = DenseDoubleTimeSeriesChunk(
        series = listOf(pv),
        rows = listOf(row(0, 10.0), row(5, 20.0), row(10, 30.0), row(25, 100.0)),
    )

    @Test
    fun builtInReductionsOverBuffer() {
        val samples = Float64Buffer(doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(4.0, ReductionSpec.Last.reduce(samples)[0])
        assertEquals(2.5, ReductionSpec.Mean.reduce(samples)[0])
        val minMaxMean = ReductionSpec.MinMaxMean.reduce(samples)
        assertEquals(listOf(1.0, 4.0, 2.5), List(minMaxMean.size) { minMaxMean[it] })
    }

    @Test
    fun namedReductionResolvesFromReducers() {
        val result = ReductionSpec.Named("const".asName()).reduce(
            samples = Float64Buffer(doubleArrayOf(1.0, 2.0)),
            reducers = mapOf("const".asName() to Reducer { Float64Buffer(doubleArrayOf(42.0)) }),
        )
        assertEquals(42.0, result[0])
    }

    @Test
    fun meanBinsGroupConsecutiveSamples() {
        val binned = chunk().reduceToBins(20.milliseconds, ReductionSpec.Mean)
        assertEquals(listOf(pv), binned.series)
        assertEquals(2, binned.rowCount)
        assertEquals(20.0, binned.value(0, 0))
        assertEquals(100.0, binned.value(1, 0))
        assertEquals(listOf(0L, 25L), List(binned.rowCount) { binned.times[it].toEpochMilliseconds() })
    }

    @Test
    fun minMaxMeanExpandsEachSeries() {
        val binned = chunk().reduceToBins(20.milliseconds, ReductionSpec.MinMaxMean)
        assertEquals(listOf("pv.min", "pv.max", "pv.mean"), binned.series.map { it.toString() })
        assertEquals(listOf(10.0, 30.0, 20.0), List(3) { binned.value(0, it) })
    }

    @Test
    fun totalizeTrapezoidIntegratesRateOverTime() {
        val flow = DenseDoubleTimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(row(0, 2.0), row(1_000, 4.0), row(2_000, 4.0)),
        )
        // (2+4)/2·1s + (4+4)/2·1s = 3 + 4
        assertEquals(7.0, flow.totalize(0), absoluteTolerance = 1e-9)
        assertEquals(7.0, flow.totalize(pv), absoluteTolerance = 1e-9)
    }

    @Test
    fun totalizeSkipsNonFiniteGaps() {
        val flow = DenseDoubleTimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(row(0, 2.0), row(1_000, Double.NaN), row(2_000, 2.0)),
        )
        assertEquals(0.0, flow.totalize(0), absoluteTolerance = 1e-9)
    }

    @Test
    fun reduceToBinsCombinesSourceQuality() {
        val bad = DataQuality(QualitySeverity.BAD, detail = "alarm")
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf(pv),
            rows = listOf(
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(1.0)),
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(5), doubleArrayOf(3.0), baselineQuality = bad),
            ),
        )
        val binned = chunk.reduceToBins(20.milliseconds, ReductionSpec.Mean)
        assertEquals(1, binned.rowCount)
        assertEquals(QualitySeverity.BAD, binned.aggregateQualityAt(0).severity)
    }

    @Test
    fun reduceToBinsPreservesSeriesOnEmptyChunk() {
        val empty = DenseDoubleTimeSeriesChunk(series = listOf(pv), rows = emptyList())
        val binned = empty.reduceToBins(20.milliseconds, ReductionSpec.MinMaxMean)
        assertEquals(0, binned.rowCount)
        assertEquals(listOf("pv.min", "pv.max", "pv.mean"), binned.series.map { it.toString() })
    }
}
