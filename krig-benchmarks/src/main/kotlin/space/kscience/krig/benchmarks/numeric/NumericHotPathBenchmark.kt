@file:Suppress("unused")

package space.kscience.krig.benchmarks.numeric

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import space.kscience.dataforge.names.asName
import space.kscience.krig.assembly.ReductionSpec
import space.kscience.krig.assembly.ResamplingMethod
import space.kscience.krig.assembly.reduceToBins
import space.kscience.krig.assembly.resample
import space.kscience.krig.assembly.totalize
import space.kscience.krig.simulation.Derivatives
import space.kscience.krig.simulation.rungeKutta4
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Numeric hot-path microbenchmarks for the KMath-backed analytics surface: dense-chunk reduction,
 * trapezoidal integration, interpolated resampling, and the deterministic RK4 integrator. These
 * cover the `Float64Buffer`/`kmath-functions` lowering that the streaming and replay paths rely on.
 */
@State(Scope.Benchmark)
open class NumericHotPathBenchmark {

    private lateinit var chunk: DenseDoubleTimeSeriesChunk

    @Setup
    open fun setup() {
        val series = listOf("rpm".asName(), "temperature".asName(), "vibration".asName())
        val rows = ArrayList<DenseDoubleTimeSeriesRow>(ROW_COUNT)
        var epochMillis = 0L
        for (i in 0 until ROW_COUNT) {
            epochMillis += SAMPLE_STEP_MILLIS
            val x = i.toDouble()
            rows += DenseDoubleTimeSeriesRow(
                time = Instant.fromEpochMilliseconds(epochMillis),
                values = doubleArrayOf(
                    1_000.0 + sin(x * 0.01) * 50.0,
                    40.0 + cos(x * 0.02) * 5.0,
                    (i % 7).toDouble(),
                ),
            )
        }
        chunk = DenseDoubleTimeSeriesChunk(series, rows)
    }

    @Benchmark
    open fun reduceToBinsMean(blackhole: Blackhole) {
        blackhole.consume(chunk.reduceToBins(BIN, ReductionSpec.Mean))
    }

    @Benchmark
    open fun reduceToBinsMinMaxMean(blackhole: Blackhole) {
        blackhole.consume(chunk.reduceToBins(BIN, ReductionSpec.MinMaxMean))
    }

    @Benchmark
    open fun totalize(blackhole: Blackhole) {
        blackhole.consume(chunk.totalize(0))
    }

    @Benchmark
    open fun resampleLinear(blackhole: Blackhole) {
        blackhole.consume(chunk.resample(RESAMPLE_STEP, ResamplingMethod.LINEAR))
    }

    @Benchmark
    open fun resampleSpline(blackhole: Blackhole) {
        blackhole.consume(chunk.resample(RESAMPLE_STEP, ResamplingMethod.SPLINE))
    }

    @Benchmark
    open fun rungeKutta4Scalar(blackhole: Blackhole): Double {
        var y = 1.0
        repeat(RK4_STEPS) { y = rungeKutta4(y, RK4_DT) { -0.5 * it } }
        blackhole.consume(y)
        return y
    }

    @Benchmark
    open fun rungeKutta4Vector(blackhole: Blackhole): Double {
        val y = doubleArrayOf(1.0, 0.0)
        val harmonic = Derivatives { state, into ->
            into[0] = state[1]
            into[1] = -state[0]
        }
        repeat(RK4_STEPS) { rungeKutta4(y, RK4_DT, harmonic) }
        blackhole.consume(y[0])
        return y[0]
    }

    private companion object {
        const val ROW_COUNT = 4_096
        const val SAMPLE_STEP_MILLIS = 5L
        const val RK4_STEPS = 1_000
        val BIN = 50.milliseconds
        val RESAMPLE_STEP = 10.milliseconds
        val RK4_DT = 1.milliseconds
    }
}
