package space.kscience.krig.transport.io

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.readByteArray

/**
 * Decoder of a continuous byte stream into discrete frames.
 * Stateful — holds a rolling buffer between invocations via [framed].
 */
public fun interface Framer<T> {
    /**
     * Attempt to read one frame from [buffer]. Return the frame and consume its bytes,
     * or `null` if insufficient bytes (caller must feed more).
     */
    public fun decode(buffer: Buffer): T?
}

/** Line-delimited frames (text-based protocols). */
public fun lineFramer(
    delimiter: ByteString = "\n".encodeToByteString(),
    maxLength: Int = 4096,
): Framer<String> = delimitedFramer(delimiter, maxLength).let { inner ->
    Framer { buffer -> inner.decode(buffer)?.decodeToString() }
}

/** Fixed-size binary frames (e.g. SPI-over-TCP). */
public fun fixedSizeFramer(size: Int): Framer<ByteArray> = Framer { buffer ->
    if (buffer.size < size) null else buffer.readByteArray(size)
}

/** Delimited by arbitrary byte sequence (e.g. `\r\n`, `<EOT>`). */
public fun delimitedFramer(
    delimiter: ByteString,
    maxLength: Int = 4096,
): Framer<ByteArray> = Framer { buffer ->
    val snapshot = buffer.peek().readByteArray()
    val index = indexOfByteString(snapshot, delimiter)
    if (index < 0) {
        require(snapshot.size <= maxLength) {
            "DelimitedFramer: no delimiter after $maxLength bytes — stream corrupt?"
        }
        null
    } else {
        val frame = buffer.readByteArray(index)
        buffer.skip(delimiter.size.toLong())
        frame
    }
}

/** Length-prefixed frames (`uint32 little-endian size` + payload). */
public fun lengthPrefixedFramer(maxPayload: Int = 1 shl 20): Framer<ByteArray> = Framer { buffer ->
    if (buffer.size < 4) return@Framer null
    val peek = buffer.peek()
    val b0 = peek.readByte().toInt() and 0xFF
    val b1 = peek.readByte().toInt() and 0xFF
    val b2 = peek.readByte().toInt() and 0xFF
    val b3 = peek.readByte().toInt() and 0xFF
    val size = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    require(size in 0..maxPayload) { "LengthPrefixedFramer: invalid size $size (max $maxPayload)" }
    if (buffer.size < 4 + size) {
        null
    } else {
        buffer.skip(4)
        buffer.readByteArray(size)
    }
}

/** Pipes this byte-chunk flow through [framer], emitting one value per complete frame. */
public fun <T> Flow<ByteArray>.framed(framer: Framer<T>): Flow<T> = flow {
    val buffer = Buffer()
    collect { chunk ->
        buffer.write(chunk)
        while (true) {
            val frame = framer.decode(buffer) ?: break
            emit(frame)
        }
    }
}

private fun indexOfByteString(haystack: ByteArray, needle: ByteString): Int {
    if (needle.size == 0 || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in 0 until needle.size) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}
