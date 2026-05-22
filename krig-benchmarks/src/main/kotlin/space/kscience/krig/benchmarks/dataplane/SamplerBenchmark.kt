@file:Suppress("unused")

package space.kscience.krig.benchmarks.dataplane

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import space.kscience.krig.core.contracts.sampling.FlowSampler
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.sampling.sampler

/** Boxed flow sampler against the primitive double ring. */
@State(Scope.Benchmark)
open class SamplerBenchmark {
    private lateinit var boxed: FlowSampler<Double>
    private lateinit var ring: RingDoubleSampler
    private var value: Double = 0.0

    @Setup
    open fun setup() {
        boxed = sampler(capacity = 1024)
        ring = doubleSampler(capacity = 1024)
    }

    @Benchmark
    open fun boxedPublishLatest(blackhole: Blackhole): Double? {
        val sample = nextValue()
        boxed.publish(sample)
        val latest = boxed.latest()
        blackhole.consume(latest)
        return latest
    }

    @Benchmark
    open fun ringPublishLatest(blackhole: Blackhole): Double {
        val sample = nextValue()
        ring.publishDouble(sample)
        val latest = ring.latestDoubleOrNaN()
        blackhole.consume(latest)
        return latest
    }

    @Benchmark
    open fun ringSnapshot(blackhole: Blackhole): Double {
        ring.publishDouble(nextValue())
        val snapshot = ring.snapshotDoubleArray()
        val latest = snapshot.lastOrNull() ?: Double.NaN
        blackhole.consume(snapshot)
        return latest
    }

    private fun nextValue(): Double {
        value += 1.0
        return value
    }
}
