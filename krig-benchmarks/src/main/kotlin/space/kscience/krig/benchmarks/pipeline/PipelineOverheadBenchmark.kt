@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)
@file:Suppress("unused")

package space.kscience.krig.benchmarks.pipeline

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.krig.core.pipeline.OperationContext
import space.kscience.krig.core.pipeline.OperationGate
import space.kscience.krig.core.pipeline.OperationKinds
import space.kscience.krig.core.pipeline.OperationPlan
import space.kscience.krig.core.pipeline.OperationPolicy
import space.kscience.krig.core.pipeline.OperationTerminal
import space.kscience.krig.core.pipeline.compileOperationExecutor

private val okGate: OperationGate = OperationGate { OperationOutcome.OkUnit }
private val benchmarkDescriptor = PropertyDescriptor(
    name = "value".asName(),
    kind = PropertyKind.LOGICAL,
    valueTypeId = TypeIds.DOUBLE,
)
private val benchmarkContext = OperationContext(OperationKinds.Read, benchmarkDescriptor.name, benchmarkDescriptor)

/** Raw suspend call against a compiled krig operation pipeline. */
@State(Scope.Benchmark)
open class PipelineOverheadBenchmark {
    private lateinit var raw: OperationTerminal
    private lateinit var plan: OperationPlan
    private lateinit var pipeline: suspend (OperationPlan, Any?, OperationTerminal) -> OperationOutcome<Any?>
    private lateinit var gatedPipeline: suspend (OperationPlan, Any?, OperationTerminal) -> OperationOutcome<Any?>

    @Setup
    open fun setup() {
        raw = { OperationOutcome.Ok(42.0) }
        plan = OperationPlan(
            context = benchmarkContext,
            policy = OperationPolicy(),
        )
        pipeline = compileOperationExecutor(
            gates = emptyList(),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
        gatedPipeline = compileOperationExecutor(
            gates = listOf(okGate),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
    }

    @Benchmark
    open fun rawCall(blackhole: Blackhole): OperationOutcome<Any?> = runBlocking {
        raw(Unit).also(blackhole::consume)
    }

    @Benchmark
    open fun compiledPipeline(blackhole: Blackhole): OperationOutcome<Any?> = runBlocking {
        pipeline(plan, Unit, raw).also(blackhole::consume)
    }

    @Benchmark
    open fun compiledPipelineWithGate(blackhole: Blackhole): OperationOutcome<Any?> = runBlocking {
        gatedPipeline(plan, Unit, raw).also(blackhole::consume)
    }
}
