@file:Suppress("unused")

package space.kscience.krig.benchmarks.dataplane

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import space.kscience.dataforge.meta.MetaConverter

/** Meta conversion against a direct typed value path. */
@State(Scope.Thread)
open class MetaConversionBenchmark {
    private var value: Double = 42.0

    @Benchmark
    open fun metaDouble(blackhole: Blackhole): Double {
        value += 0.001
        val meta = MetaConverter.double.convert(value)
        val out = MetaConverter.double.read(meta)
        blackhole.consume(meta)
        blackhole.consume(out)
        return out
    }

    @Benchmark
    open fun typedDouble(blackhole: Blackhole): Double {
        value += 0.001
        val out = value
        blackhole.consume(out)
        return out
    }
}
