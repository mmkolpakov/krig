package space.kscience.krig.api.result

import kotlinx.benchmark.*
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.dataforge.names.asName

/**
 * JMH-backed micro-benchmark comparing [OperationOutcome]-based fault propagation
 * against the throw/catch path. Warmup and iteration counts are controlled by
 * the benchmark Gradle task.
 *
 * Run: `./gradlew :krig-contracts:jvmBenchmark`
 */
@State(Scope.Benchmark)
open class OperationOutcomeBenchmark {

    private val sampleFault: OperationFault = GenericOperationFault(
        type = "bench.fault".asName(),
        message = "synthetic fault for benchmarking",
    )

    /**
     * Outcome path — no stack trace, just an allocation + `when`-pattern-match.
     * This is the model for `PipelineDevice.readPropertyOutcome`.
     */
    @Benchmark
    fun outcomePath(blackhole: Blackhole): OperationOutcome<Int> {
        val result: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        val out = when (result) {
            is OperationOutcome.Ok -> OperationOutcome.Ok(result.value + 1)
            is OperationOutcome.Fail -> result
        }
        blackhole.consume(out)
        return out
    }

    /**
     * Throw/catch path — fills in stack trace on each failure.
     * This is the model for `Device.readProperty` on a throwing backend.
     */
    @Benchmark
    fun throwPath(blackhole: Blackhole): Int {
        val result: Int = try {
            throw OperationFaultException(sampleFault)
        } catch (ex: OperationFaultException) {
            ex.fault.faultType.toString().length
        }
        blackhole.consume(result)
        return result
    }
}
