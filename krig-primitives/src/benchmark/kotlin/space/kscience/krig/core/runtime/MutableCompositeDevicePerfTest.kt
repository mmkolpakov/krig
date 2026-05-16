@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.runtime

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.benchmark.*
import kotlinx.coroutines.runBlocking
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

private val contextSeq: AtomicInt = AtomicInt(0)
private fun freshContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

/**
 * JMH-backed throughput benchmark for [MutableCompositeDevice] attach/detach.
 * Budget 500ms catches order-of-magnitude regressions. Warmup and iteration
 * counts are controlled by the benchmark Gradle task, not hard-coded.
 *
 * Run: `./gradlew :krig-primitives:jvmBenchmark`
 */
@State(Scope.Benchmark)
open class MutableCompositeDeviceAttachBenchmark {

    private class LeafDevice(name: Name) : AbstractDevice(name, DeviceRuntime(freshContext(name.toString()))) {
        override suspend fun readProperty(propertyName: Name) = error("bench-only")
        override suspend fun writeProperty(propertyName: Name, value: space.kscience.dataforge.meta.Meta) = Unit
        override suspend fun execute(actionName: Name, argument: space.kscience.dataforge.meta.Meta?) = null
    }

    private lateinit var hub: MutableCompositeDevice
    private lateinit var devices: List<Pair<Name, LeafDevice>>

    @Setup
    fun setup() {
        val ctx = freshContext("bench")
        hub = MutableCompositeDevice("hub".asName(), ctx)
        devices = (0 until 1000).map { i ->
            val n = "${ctx.name}.d$i".asName()
            n to LeafDevice(n)
        }
    }

    @Benchmark
    fun attach1000(): MutableCompositeDevice {
        devices.forEach { (n, d) -> hub.attach(n, d) }
        return hub
    }

    @Benchmark
    fun attachAndDetach1000(blackhole: Blackhole) = runBlocking {
        devices.forEach { (n, d) -> hub.attach(n, d) }
        devices.forEach { (n, _) -> hub.detach(n, DeviceDepartureReason.Graceful) }
        blackhole.consume(hub)
    }
}
