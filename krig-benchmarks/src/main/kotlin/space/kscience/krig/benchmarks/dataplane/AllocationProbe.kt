package space.kscience.krig.benchmarks.dataplane

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import space.kscience.krig.core.contracts.sampling.FlowSampler
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.sampling.sampler

/**
 * Allocation probe for the telemetry hot path.
 *
 * Measures normalized allocation (bytes per operation) for the boxed [FlowSampler] versus the
 * primitive [RingDoubleSampler], directly substantiating the "zero-allocation write path" claim
 * without relying on JMH profiler wiring. Allocation is read per-thread via
 * [ThreadMXBean.getThreadAllocatedBytes], the same quantity JMH reports as `gc.alloc.rate.norm`.
 *
 * Run with: `./gradlew :krig-benchmarks:allocationProbe`.
 */
private val threadBean: ThreadMXBean =
    ManagementFactory.getThreadMXBean() as ThreadMXBean

private fun allocatedBytes(): Long =
    threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId())

private class ProbeResult(
    val name: String,
    val bytesPerOp: Double,
    val ops: Long,
)

private inline fun measure(
    name: String,
    ops: Long,
    warmup: Long,
    block: (Long) -> Double,
): ProbeResult {
    // Warm up so the JIT settles and steady-state allocation behaviour is measured.
    var warmSink = 0.0
    for (i in 0L until warmup) warmSink += block(i)
    if (warmSink.isNaN()) error("unreachable") // keep warmup observable

    val before = allocatedBytes()
    var sink = 0.0
    for (i in 0L until ops) sink += block(i)
    val after = allocatedBytes()

    // Consume sink to prevent dead-code elimination of the measured loop.
    if (sink == Double.NEGATIVE_INFINITY) println("unreachable $sink")

    val total = (after - before).coerceAtLeast(0)
    return ProbeResult(name, total.toDouble() / ops, ops)
}

fun main() {
    val ops = 5_000_000L
    val warmup = 2_000_000L

    val boxed: FlowSampler<Double> = sampler(capacity = 1024)
    val ring: RingDoubleSampler = doubleSampler(capacity = 1024)
    var counter = 0.0

    val boxedResult = measure("boxed.publish+latest (FlowSampler<Double>)", ops, warmup) {
        counter += 1.0
        boxed.publish(counter)
        boxed.latest() ?: 0.0
    }
    val ringResult = measure("ring.publishDouble+latest (RingDoubleSampler)", ops, warmup) {
        counter += 1.0
        ring.publishDouble(counter)
        ring.latestDoubleOrNaN()
    }
    // Snapshot allocates a DoubleArray copy by design; measured separately and NOT claimed zero-alloc.
    val snapshotOps = 200_000L
    val snapshotResult = measure("ring.snapshotDoubleArray (copy, NOT hot path)", snapshotOps, 50_000L) {
        counter += 1.0
        ring.publishDouble(counter)
        ring.snapshotDoubleArray().lastOrNull() ?: 0.0
    }

    val results = listOf(boxedResult, ringResult, snapshotResult)
    printResults(results)
    writeReport(results)
}

private fun printResults(results: List<ProbeResult>) {
    println("krig allocation probe (hot path)")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println()
    println("| scenario | ops | bytes/op |")
    println("|---|---:|---:|")
    for (r in results) {
        println("| ${r.name} | ${r.ops} | ${"%.2f".format(r.bytesPerOp)} |")
    }
}

private fun writeReport(results: List<ProbeResult>) {
    val root = Path("build/krig-benchmarks")
    root.createDirectories()
    val sb = StringBuilder()
    sb.appendLine("# krig allocation probe (hot path)")
    sb.appendLine()
    sb.appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    sb.appendLine("Method: per-thread allocated bytes via com.sun.management.ThreadMXBean (== JMH gc.alloc.rate.norm).")
    sb.appendLine()
    sb.appendLine("| scenario | ops | bytes/op |")
    sb.appendLine("|---|---:|---:|")
    for (r in results) {
        sb.appendLine("| ${r.name} | ${r.ops} | ${"%.2f".format(r.bytesPerOp)} |")
    }
    sb.appendLine()
    sb.appendLine("The ring write path (`publishDouble` + `latestDoubleOrNaN`) is expected to be ~0 B/op")
    sb.appendLine("(no boxing, write into a preallocated DoubleArray). The boxed FlowSampler path allocates")
    sb.appendLine("a wrapper per sample. `snapshotDoubleArray` allocates a copy by design and is not a hot-path claim.")
    root.resolve("allocation-results.md").writeText(sb.toString())
}
