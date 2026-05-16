package space.kscience.krig.transport.io

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class FramerTest {

    @Test
    fun lineFramerSplitsOnDelimiter() = runTest {
        val chunks = flowOf("hello\n".encodeToByteArray(), "world\nabc".encodeToByteArray(), "\n".encodeToByteArray())
        val frames = chunks.framed(LineFramer()).toList()
        assertEquals(listOf("hello", "world", "abc"), frames)
    }

    @Test
    fun fixedSizeFramerEmitsExactChunks() = runTest {
        val chunks = flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7), byteArrayOf(8))
        val frames = chunks.framed(FixedSizeFramer(4)).toList()
        assertEquals(2, frames.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), frames[0].toList())
        assertEquals(listOf<Byte>(5, 6, 7, 8), frames[1].toList())
    }

    @Test
    fun delimitedFramerSupportsMultiByteDelimiter() = runTest {
        val chunks = flowOf("frame1\r\n".encodeToByteArray(), "frame2\r\nfra".encodeToByteArray(), "gment\r\n".encodeToByteArray())
        val frames = chunks.framed(DelimitedFramer("\r\n".encodeToByteString())).toList()
        assertEquals(listOf("frame1", "frame2", "fragment"), frames.map { it.decodeToString() })
    }

    @Test
    fun lengthPrefixedFramerParsesHeader() = runTest {
        // [3 bytes length=3 (u32 le)] [payload 'ABC']
        val packet = byteArrayOf(3, 0, 0, 0) + "ABC".encodeToByteArray()
        val frames = flowOf(packet).framed(LengthPrefixedFramer()).toList()
        assertEquals(1, frames.size)
        assertEquals("ABC", frames[0].decodeToString())
    }
}
