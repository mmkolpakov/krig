@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
)
@file:Suppress("unused")

package space.kscience.krig.benchmarks.runtime

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.runtime.MutableDeviceHub

/** Hub topology attach/detach throughput. */
@State(Scope.Benchmark)
open class MutableDeviceHubAttachBenchmark {

    private lateinit var hub: MutableDeviceHub
    private lateinit var devices: List<Pair<Name, BenchLeafDevice>>

    @Setup
    open fun setup() {
        val context = benchContext("bench")
        hub = benchHub(context)
        devices = benchLeaves(context, count = 1000)
    }

    @Benchmark
    open fun attachAndDetach1000(blackhole: Blackhole) = runBlocking {
        devices.forEach { (n, d) -> hub.attach(n, d) }
        devices.forEach { (n, _) -> hub.detach(n, DeviceDepartureReason.Graceful) }
        blackhole.consume(hub)
    }
}
