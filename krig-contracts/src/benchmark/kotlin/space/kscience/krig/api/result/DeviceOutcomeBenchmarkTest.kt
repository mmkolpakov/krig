package space.kscience.krig.api.result

import kotlinx.benchmark.*
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault

/**
 * JMH-backed micro-benchmark comparing [DeviceOutcome]-based fault propagation
 * against the throw/catch path. Warmup and iteration counts are controlled by
 * the benchmark Gradle task.
 *
 * Run: `./gradlew :krig-contracts:jvmBenchmark`
 */
@State(Scope.Benchmark)
open class DeviceOutcomeBenchmark {

    private val sampleFault: DeviceFault = GenericDeviceFault(
        code = "BENCH_FAULT",
        message = "synthetic fault for benchmarking",
    )

    /**
     * Outcome path — no stack trace, just an allocation + `when`-pattern-match.
     * This is the model for `PipelinedDevice.readPropertyOutcome`.
     */
    @Benchmark
    fun outcomePath(blackhole: Blackhole): DeviceOutcome<Int> {
        val result: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        val out = when (result) {
            is DeviceOutcome.Ok -> DeviceOutcome.Ok(result.value + 1)
            is DeviceOutcome.Fail -> result
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
            throw DeviceFaultException(sampleFault)
        } catch (ex: DeviceFaultException) {
            ex.fault.code.length
        }
        blackhole.consume(result)
        return result
    }
}
