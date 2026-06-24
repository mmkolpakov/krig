@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("MagicNumber")

package space.kscience.krig.benchmarks.transport

import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.serialization.krigApiSerializersModule
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.arrow.ArrowCompression
import space.kscience.krig.arrow.writeArrowIpc
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow

/**
 * Shared builders for the transport-discipline probe and its JMH companion. All artifacts are built
 * through the public KRig SDK API: [PropertyChangedMessage] + [krigJson] for the per-message path and
 * [DenseDoubleTimeSeriesChunk.writeArrowIpc] for the columnar batch. Binary codecs ([Cbor], [ProtoBuf])
 * reuse the public [krigApiSerializersModule] so polymorphic `DeviceMessage` discrimination round-trips.
 */
internal val deviceMessageSerializer = PolymorphicSerializer(DeviceMessage::class)
internal val transportJson: Json = krigJson()
internal val transportCbor: Cbor = Cbor { serializersModule = krigApiSerializersModule }
internal val transportProto: ProtoBuf = ProtoBuf { serializersModule = krigApiSerializersModule }

internal val benchSourceDevice: Name = "engine".asName()
internal val benchPropertyName: Name = "rpm".asName()

internal fun sampleMessages(count: Int): List<PropertyChangedMessage> =
    List(count) { index ->
        PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(index.toLong()),
            property = benchPropertyName,
            value = Meta((1000.0 + (index % 500)).asValue()),
            sourceDevice = benchSourceDevice,
        )
    }

internal fun jsonBytes(messages: List<PropertyChangedMessage>): Long =
    messages.sumOf { transportJson.encodeToString(deviceMessageSerializer, it).encodeToByteArray().size.toLong() }

internal fun cborBytes(messages: List<PropertyChangedMessage>): Long =
    messages.sumOf { transportCbor.encodeToByteArray(deviceMessageSerializer, it).size.toLong() }

internal fun protoBytes(messages: List<PropertyChangedMessage>): Long =
    messages.sumOf { transportProto.encodeToByteArray(deviceMessageSerializer, it).size.toLong() }

internal fun denseChunk(rowCount: Int, width: Int): DenseDoubleTimeSeriesChunk {
    val series = List(width) { "pv$it".asName() }
    val rows = List(rowCount) { row ->
        DenseDoubleTimeSeriesRow(
            time = Instant.fromEpochMilliseconds(row.toLong()),
            values = DoubleArray(width) { column -> 1000.0 + (row % 500) + column * 0.01 },
        )
    }
    return DenseDoubleTimeSeriesChunk(series = series, rows = rows)
}

internal fun arrowBytes(chunk: DenseDoubleTimeSeriesChunk, compression: ArrowCompression): Long {
    val sink = ByteArrayOutputStream()
    Channels.newChannel(sink).use { channel -> chunk.writeArrowIpc(channel, compression) }
    return sink.size().toLong()
}
