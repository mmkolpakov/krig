@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)
@file:Suppress("unused")

package space.kscience.krig.benchmarks.pipeline

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.krig.core.pipeline.compileOperationExecutor

private val okGate: suspend () -> OperationOutcome<Unit> = { OperationOutcome.OkUnit }

/** Raw suspend call against a compiled krig operation pipeline. */
@State(Scope.Benchmark)
open class PipelineOverheadBenchmark {
    private lateinit var raw: suspend (Unit) -> OperationOutcome<Double>
    private lateinit var pipeline: suspend (Unit) -> OperationOutcome<Double>
    private lateinit var gatedPipeline: suspend (Unit) -> OperationOutcome<Double>

    @Setup
    open fun setup() {
        raw = { OperationOutcome.Ok(42.0) }
        pipeline = compileOperationExecutor(
            timeout = null,
            retry = null,
            gates = emptyList(),
            registry = ResourceLockRegistry(),
            locks = emptyList(),
            observers = { _, _ -> },
            terminal = raw,
        )
        gatedPipeline = compileOperationExecutor(
            timeout = null,
            retry = null,
            gates = listOf(okGate),
            registry = ResourceLockRegistry(),
            locks = emptyList(),
            observers = { _, _ -> },
            terminal = raw,
        )
    }

    @Benchmark
    open fun rawCall(blackhole: Blackhole): OperationOutcome<Double> = runBlocking {
        raw(Unit).also(blackhole::consume)
    }

    @Benchmark
    open fun compiledPipeline(blackhole: Blackhole): OperationOutcome<Double> = runBlocking {
        pipeline(Unit).also(blackhole::consume)
    }

    @Benchmark
    open fun compiledPipelineWithGate(blackhole: Blackhole): OperationOutcome<Double> = runBlocking {
        gatedPipeline(Unit).also(blackhole::consume)
    }
}
