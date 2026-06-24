@file:Suppress("unused")

package space.kscience.krig.benchmarks.transport

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.arrow.ArrowCompression
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk

/**
 * Throughput companion for [TelemetryTransportProbe]: encode cost of one batch of telemetry via the
 * three transport disciplines (per-message JSON/CBOR/PROTO vs one columnar Arrow batch). Bytes are
 * consumed by the blackhole so the JIT cannot elide the encode.
 *
 * Note: Apache Arrow Java needs `--add-opens=java.base/java.nio=ALL-UNNAMED` and
 * `--enable-native-access=ALL-UNNAMED` on the forked JMH JVM.
 */
@State(Scope.Benchmark)
open class TransportEncodingBenchmark {
    private lateinit var messages: List<PropertyChangedMessage>
    private lateinit var chunk: DenseDoubleTimeSeriesChunk

    @Setup
    open fun setup() {
        messages = sampleMessages(BATCH)
        chunk = denseChunk(BATCH, width = 1)
    }

    @Benchmark
    open fun perMessageJson(blackhole: Blackhole): Unit = blackhole.consume(jsonBytes(messages))

    @Benchmark
    open fun perMessageCbor(blackhole: Blackhole): Unit = blackhole.consume(cborBytes(messages))

    @Benchmark
    open fun perMessageProto(blackhole: Blackhole): Unit = blackhole.consume(protoBytes(messages))

    @Benchmark
    open fun arrowBatchNone(blackhole: Blackhole): Unit =
        blackhole.consume(arrowBytes(chunk, ArrowCompression.NONE))

    @Benchmark
    open fun arrowBatchZstd(blackhole: Blackhole): Unit =
        blackhole.consume(arrowBytes(chunk, ArrowCompression.ZSTD))

    private companion object {
        private const val BATCH = 4096
    }
}
