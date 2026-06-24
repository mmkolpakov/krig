@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("MagicNumber", "unused", "LongMethod")

package space.kscience.krig.benchmarks.transport

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import space.kscience.krig.benchmarks.bootstrapMedianCi
import space.kscience.krig.benchmarks.meanValue
import space.kscience.krig.benchmarks.median
import space.kscience.krig.benchmarks.percentile
import space.kscience.krig.benchmarks.sampleStdDev
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.arrow.ArrowCompression
import space.kscience.magix.api.MagixMessage

/**
 * Telemetry transport-discipline probe.
 *
 * Measures the cost of delivering N high-frequency samples edge -> analytics tier with three
 * disciplines, all built on the public KRig SDK API:
 *   1. per-sample [PropertyChangedMessage] serialized as JSON (the canonical Magix wire);
 *   2. the same messages serialized with a binary codec (CBOR / ProtoBuf), mirroring the
 *      `encodeMessage(...)` PROTO/CBOR options of the controls-kt Magix RSocket server;
 *   3. one columnar Arrow/feather batch ([space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk]).
 *
 * Reported metrics: bytes, bytes/value, hot allocation (bytes/op via [ThreadMXBean], the quantity
 * JMH reports as `gc.alloc.rate.norm`) and encode time. We measure the *format* cost, not a broker
 * or the network: no live [space.kscience.magix.api.MagixEndpoint] is required.
 *
 * Run with: `./gradlew :krig-benchmarks:transportProbe`.
 */
private val threadBean: ThreadMXBean = ManagementFactory.getThreadMXBean() as ThreadMXBean

private fun allocatedBytes(): Long = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId())

/** N sweep for the bytes/value comparison; representative N for allocation/time. */
private val sampleSweep = intArrayOf(1, 16, 256, 4096, 65536)
private const val REPRESENTATIVE_N = 4096
private const val WIDE_ROWS = 256
private const val WIDE_SERIES = 32

/** JSON of the full Magix envelope (payload = JsonElement), to expose constant per-frame overhead. */
private fun magixEnvelopeJsonBytes(messages: List<PropertyChangedMessage>): Long =
    messages.sumOf { message ->
        val envelope = MagixMessage(
            format = "krig.device.v1",
            payload = transportJson.encodeToJsonElement(deviceMessageSerializer, message),
            sourceEndpoint = benchSourceDevice,
        )
        transportJson.encodeToString(MagixMessage.serializer(), envelope).encodeToByteArray().size.toLong()
    }

private val probeLocale: Locale = Locale.US

private class Measured(
    val allocBytesPerBatch: Double,
    val nanosPerBatch: List<Double>,
)

/**
 * Collects [samples] independent measurements, each averaging [innerReps] batch encodes, and retains
 * the per-batch nanosecond samples so the report can give the distributional figures a qualification
 * study expects (median, p95/p99, percentile-bootstrap CI; computed via kmath-stat). Allocation per
 * batch is deterministic for a fixed batch, so only its mean is reported.
 */
private inline fun measure(samples: Int, innerReps: Int, warmup: Int, block: () -> Long): Measured {
    var sink = 0L
    repeat(warmup) { sink += block() }
    val perBatchNanos = DoubleArray(samples)
    var allocTotal = 0L
    repeat(samples) { s ->
        val before = allocatedBytes()
        val start = System.nanoTime()
        repeat(innerReps) { sink += block() }
        val elapsed = System.nanoTime() - start
        allocTotal += (allocatedBytes() - before).coerceAtLeast(0)
        perBatchNanos[s] = elapsed.toDouble() / innerReps
    }
    if (sink == Long.MIN_VALUE) println("unreachable $sink")
    return Measured(allocTotal.toDouble() / (samples.toLong() * innerReps), perBatchNanos.toList())
}

@Suppress("TooGenericExceptionCaught")
private fun roundTrip(): List<String> {
    val sample = sampleMessages(1).first()
    val lines = mutableListOf<String>()
    runCatching {
        val text = transportJson.encodeToString(deviceMessageSerializer, sample)
        val ok = transportJson.decodeFromString(deviceMessageSerializer, text) == sample
        lines += "- JSON round-trip: ${if (ok) "OK" else "MISMATCH"}"
    }.onFailure { lines += "- JSON round-trip: FAILED (${it::class.simpleName}: ${it.message})" }
    runCatching {
        val bytes = transportCbor.encodeToByteArray(deviceMessageSerializer, sample)
        val ok = transportCbor.decodeFromByteArray(deviceMessageSerializer, bytes) == sample
        lines += "- CBOR round-trip: ${if (ok) "OK" else "MISMATCH"}"
    }.onFailure { lines += "- CBOR round-trip: FAILED (${it::class.simpleName}: ${it.message})" }
    runCatching {
        val bytes = transportProto.encodeToByteArray(deviceMessageSerializer, sample)
        val ok = transportProto.decodeFromByteArray(deviceMessageSerializer, bytes) == sample
        lines += "- PROTO round-trip: ${if (ok) "OK" else "MISMATCH"}"
    }.onFailure { lines += "- PROTO round-trip: FAILED (${it::class.simpleName}: ${it.message})" }
    return lines
}

@Suppress("TooGenericExceptionCaught")
private fun bytesPerValueTable(): List<String> {
    val rows = mutableListOf<String>()
    rows += "| N | msg JSON | msg CBOR | msg PROTO | Magix-env JSON | Arrow NONE | Arrow ZSTD |"
    rows += "|---:|---:|---:|---:|---:|---:|---:|"
    for (n in sampleSweep) {
        val messages = sampleMessages(n)
        val chunk = denseChunk(n, width = 1)
        fun perValue(total: Long): String = "%.2f".format(total.toDouble() / n)
        val protoCell = runCatching { perValue(protoBytes(messages)) }.getOrElse { "—" }
        val envCell = runCatching { perValue(magixEnvelopeJsonBytes(messages)) }.getOrElse { "—" }
        rows += "| $n | ${perValue(jsonBytes(messages))} | ${perValue(cborBytes(messages))} | " +
            "$protoCell | $envCell | ${perValue(arrowBytes(chunk, ArrowCompression.NONE))} | " +
            "${perValue(arrowBytes(chunk, ArrowCompression.ZSTD))} |"
    }
    return rows
}

@Suppress("TooGenericExceptionCaught")
private fun allocTimeTable(): List<String> {
    val n = REPRESENTATIVE_N
    val messages = sampleMessages(n)
    val chunk = denseChunk(n, width = 1)
    val samples = 30
    val innerReps = 20
    val warmup = 50

    val jsonM = measure(samples, innerReps, warmup) { jsonBytes(messages) }
    val cborM = measure(samples, innerReps, warmup) { cborBytes(messages) }
    val protoM = runCatching { measure(samples, innerReps, warmup) { protoBytes(messages) } }.getOrNull()
    val arrowNoneM = measure(samples, innerReps, warmup) { arrowBytes(chunk, ArrowCompression.NONE) }
    val arrowZstdM = measure(samples, innerReps, warmup) { arrowBytes(chunk, ArrowCompression.ZSTD) }

    fun row(name: String, m: Measured?): String =
        if (m == null) {
            "| $name | — | — | — | — |"
        } else {
            val perValueUs = m.nanosPerBatch.map { it / n / 1000.0 }
            val (lo, hi) = perValueUs.bootstrapMedianCi()
            val alloc = "%.2f".format(probeLocale, m.allocBytesPerBatch / n)
            val mean = "%.3f ± %.3f".format(probeLocale, perValueUs.meanValue(), perValueUs.sampleStdDev())
            val medianCi = "%.3f [%.3f; %.3f]".format(probeLocale, perValueUs.median(), lo, hi)
            val tail = "%.3f / %.3f".format(probeLocale, perValueUs.percentile(0.95), perValueUs.percentile(0.99))
            "| $name | $alloc | $mean | $medianCi | $tail |"
        }

    val rows = mutableListOf<String>()
    rows += "Representative N = $n, width = 1; $samples samples × $innerReps reps each. " +
        "Encode µs/value via kmath-stat: mean ± StdDev, median [95% bootstrap CI], p95 / p99."
    rows += ""
    rows += "| path | alloc bytes/value | µs/value mean ± StdDev | µs/value median [95% CI] | µs/value p95 / p99 |"
    rows += "|---|---:|---:|---:|---:|"
    rows += row("per-message JSON", jsonM)
    rows += row("per-message CBOR", cborM)
    rows += row("per-message PROTO", protoM)
    rows += row("Arrow batch (NONE)", arrowNoneM)
    rows += row("Arrow batch (ZSTD)", arrowZstdM)
    return rows
}

private fun wideScenario(): List<String> {
    val totalValues = WIDE_ROWS * WIDE_SERIES
    val wideMessages = buildList {
        repeat(WIDE_ROWS) { r ->
            repeat(WIDE_SERIES) { c ->
                add(
                    PropertyChangedMessage(
                        time = Instant.fromEpochMilliseconds(r.toLong()),
                        property = "pv$c".asName(),
                        value = Meta((1000.0 + (r % 500) + c).asValue()),
                        sourceDevice = benchSourceDevice,
                    ),
                )
            }
        }
    }
    val chunk = denseChunk(WIDE_ROWS, WIDE_SERIES)
    fun perValue(total: Long): String = "%.2f".format(total.toDouble() / totalValues)
    val rows = mutableListOf<String>()
    rows += "Wide case: $WIDE_ROWS rows × $WIDE_SERIES series = $totalValues values."
    rows += ""
    rows += "| representation | bytes/value |"
    rows += "|---|---:|"
    rows += "| per-message JSON | ${perValue(jsonBytes(wideMessages))} |"
    rows += "| per-message CBOR | ${perValue(cborBytes(wideMessages))} |"
    rows += "| Arrow batch (NONE) | ${perValue(arrowBytes(chunk, ArrowCompression.NONE))} |"
    rows += "| Arrow batch (ZSTD) | ${perValue(arrowBytes(chunk, ArrowCompression.ZSTD))} |"
    return rows
}

fun main() {
    val env = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
    val report = buildString {
        appendLine("# krig telemetry transport probe")
        appendLine()
        appendLine("JVM: $env")
        appendLine("Method: per-message DeviceMessage encode (JSON/CBOR/PROTO) vs columnar Arrow batch.")
        appendLine("Allocation via com.sun.management.ThreadMXBean (== JMH gc.alloc.rate.norm). Format only, no network.")
        appendLine()
        appendLine("## Round-trip integrity")
        appendLine()
        roundTrip().forEach { appendLine(it) }
        appendLine()
        appendLine("## Bytes per value (sweep over N, single series)")
        appendLine()
        bytesPerValueTable().forEach { appendLine(it) }
        appendLine()
        appendLine("## Allocation and encode time")
        appendLine()
        allocTimeTable().forEach { appendLine(it) }
        appendLine()
        appendLine("## Wide scenario (columnar advantage)")
        appendLine()
        wideScenario().forEach { appendLine(it) }
        appendLine()
    }
    print(report)
    val root = Path("build/krig-benchmarks")
    root.createDirectories()
    root.resolve("transport-results.md").writeText(report)
    println("Report written to build/krig-benchmarks/transport-results.md")
}
