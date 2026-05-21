@file:Suppress("unused")

package space.kscience.krig.benchmarks.operation

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.result.OperationOutcome

/** Outcome fault path vs exception path. */
@State(Scope.Benchmark)
open class OperationOutcomeBenchmark {

    private val sampleFault: OperationFault = GenericOperationFault(
        faultType = "bench.fault".asName(),
        message = "synthetic fault for benchmarking",
    )

    @Benchmark
    open fun outcomePath(blackhole: Blackhole): OperationOutcome<Int> {
        val out: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        blackhole.consume(out)
        return out
    }

    @Benchmark
    open fun throwPath(blackhole: Blackhole): Int {
        val result: Int = try {
            throw OperationFaultException(sampleFault)
        } catch (ex: OperationFaultException) {
            ex.fault.faultType.toString().length
        }
        blackhole.consume(result)
        return result
    }
}
