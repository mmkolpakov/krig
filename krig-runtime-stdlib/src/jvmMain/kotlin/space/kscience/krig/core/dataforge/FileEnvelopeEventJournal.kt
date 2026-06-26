package space.kscience.krig.core.dataforge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.io.TaggedEnvelopeFormat
import space.kscience.dataforge.io.io
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.storage.journal.EventCursor
import space.kscience.krig.storage.journal.EventJournal
import space.kscience.krig.storage.journal.ReplayRecord
import space.kscience.krig.storage.journal.SequenceCursor
import space.kscience.krig.storage.journal.compareEnvelopesByCausality
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Instant

/**
 * Durable, append-only [EventJournal] backed by a single file of concatenated DataForge envelopes
 * (`TaggedEnvelopeFormat`, DF02). Each [DeviceMessageFrame] is lowered to an `Envelope` by [codec],
 * then framed by the self-delimiting envelope tag — so the log is replayable without a database.
 *
 * JVM-only: byte-offset positioning and tail truncation use `java.nio` (`FileChannel`). On construction
 * the offset index is rebuilt by a *streaming* scan that reads only the fixed-size envelope tags
 * (never the whole file), so opening a multi-gigabyte journal stays O(records) small reads; a torn
 * trailing envelope (interrupted write / partial flush) is truncated away so appends stay consistent.
 *
 * [replayFrom] seeks via the offset index (O(1) resume from a [SequenceCursor]); replay decodes one
 * record at a time with full-read loops — memory is bounded by the largest single envelope.
 * Intended for replay/audit, not high-rate time-series storage.
 */
public class FileEnvelopeEventJournal(
    private val path: Path,
    context: Context = Global,
    private val codec: DeviceMessageFrameCodec = KotlinxJsonDeviceMessageFrameCodec(),
    tailBufferCapacity: Int = 64,
) : EventJournal {

    private val format = TaggedEnvelopeFormat(context.io)

    // Coroutine Mutex (not a blocking monitor): the suspending append/read paths must not pin a
    // dispatcher thread while holding the lock, and blocking channel IO is offloaded to Dispatchers.IO.
    private val lock = Mutex()

    /** `offsets[sequence]` = byte offset of that envelope in the file. */
    private val offsets = ArrayList<Long>()
    private var fileLength: Long = 0

    private val channel: FileChannel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    )

    private val tail = MutableSharedFlow<DeviceMessageFrame<DeviceMessage>>(
        extraBufferCapacity = tailBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        recoverIndex()
    }

    /** Streaming index recovery: per record only its envelope tag is read; payloads are skipped. */
    private fun recoverIndex() {
        val size = channel.size()
        var offset = 0L
        while (offset < size) {
            val recordSize = parseRecordSizeAt(offset, size) ?: break // torn / corrupt tail
            offsets.add(offset)
            offset += recordSize
        }
        fileLength = offset
        if (offset < size) {
            channel.truncate(offset) // drop the torn tail
        }
    }

    /**
     * Parses the `TaggedEnvelopeFormat` tag at [offset] and returns the full record size
     * (tag + meta + data), or `null` when the bytes do not form a complete, well-formed record.
     * Layout (big-endian): `#~` + version(4) + metaFormatKey(short) + metaSize(uint) +
     * dataSize(uint for DF02 / ulong for DF03) + `~#\r\n`.
     */
    private fun parseRecordSizeAt(offset: Long, fileSize: Long): Long? {
        val prefix = ByteBuffer.allocate(6)
        if (offset + prefix.capacity() > fileSize) return null
        if (!readFully(prefix, offset)) return null
        val prefixBytes = prefix.array()
        if (prefixBytes[0] != '#'.code.toByte() || prefixBytes[1] != '~'.code.toByte()) return null
        val tagSize = when (prefixBytes.decodeToString(2, 6)) {
            "DF02" -> 20
            "DF03" -> 24
            else -> return null
        }
        if (offset + tagSize > fileSize) return null
        val tag = ByteBuffer.allocate(tagSize)
        if (!readFully(tag, offset)) return null
        tag.flip()
        tag.position(6) // start sequence + version
        tag.short // meta format key — not needed for sizing
        val metaSize = tag.int.toUInt().toLong()
        val dataSize = if (tagSize == 20) tag.int.toUInt().toLong() else tag.long
        val endOk = tag.get() == '~'.code.toByte() &&
                tag.get() == '#'.code.toByte() &&
                tag.get() == '\r'.code.toByte() &&
                tag.get() == '\n'.code.toByte()
        if (!endOk) return null
        val total = tagSize + metaSize + dataSize
        return if (offset + total <= fileSize) total else null
    }

    /**
     * Fills [buffer] from [position] with a read loop — `FileChannel.read` does not guarantee a
     * full buffer in one call. Returns `false` when EOF arrives before the buffer is full.
     */
    private fun readFully(buffer: ByteBuffer, position: Long): Boolean {
        var pos = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, pos)
            if (read < 0) return false
            pos += read
        }
        return true
    }

    override suspend fun write(event: DeviceMessageFrame<DeviceMessage>): EventCursor {
        val bytes = Buffer().also { format.writeTo(it, codec.encode(event)) }.readByteArray()
        val cursor = lock.withLock {
            // Blocking append (write + fsync) is offloaded so it never starves the calling dispatcher.
            withContext(Dispatchers.IO) {
                channel.position(fileLength)
                val out = ByteBuffer.wrap(bytes)
                while (out.hasRemaining()) channel.write(out)
                channel.force(false)
            }
            val sequence = offsets.size.toLong()
            offsets.add(fileLength)
            fileLength += bytes.size
            SequenceCursor(sequence)
        }
        tail.tryEmit(event)
        return cursor
    }

    override fun readAll(): Flow<DeviceMessageFrame<DeviceMessage>> =
        flow { collectFrom(startSequence = 0) { _, frame -> emit(frame) } }

    override fun replayFrom(after: EventCursor?): Flow<ReplayRecord> {
        val startSequence = if (after == null) 0L else (after as SequenceCursor).sequence + 1
        return flow {
            collectFrom(startSequence) { sequence, frame ->
                emit(ReplayRecord(SequenceCursor(sequence), frame))
            }
        }
    }

    /**
     * Causally-ordered time-window replay: the in-range window is buffered and sorted by HLC (then
     * sequence), so reconstruction order is independent of the write/merge interleaving (unlike the
     * write-ordered [read]). Memory is bounded by the window size.
     */
    override fun replayRecords(from: Instant, until: Instant): Flow<ReplayRecord> = flow {
        if (until < from) return@flow
        val window = ArrayList<ReplayRecord>()
        collectFrom(startSequence = 0) { sequence, frame ->
            if (frame.payload.time in from..until) window.add(ReplayRecord(SequenceCursor(sequence), frame))
        }
        window.sortWith(::compareRecordsByCausality)
        window.forEach { emit(it) }
    }

    private fun compareRecordsByCausality(left: ReplayRecord, right: ReplayRecord): Int {
        val byCausal = compareEnvelopesByCausality(left.envelope, right.envelope)
        if (byCausal != 0) return byCausal
        return (left.cursor as SequenceCursor).sequence.compareTo((right.cursor as SequenceCursor).sequence)
    }

    override fun observe(): Flow<DeviceMessageFrame<DeviceMessage>> = tail.asSharedFlow()

    override fun close(): Unit = channel.close()

    /**
     * Decodes records one at a time against an offset snapshot taken under the [lock]. Each record's
     * bytes are read on [Dispatchers.IO] (blocking `FileChannel.read` must not pin the collector's
     * dispatcher) with a full-read loop ([readFully]) — a partial read must not silently truncate the
     * replayed history. Memory use is bounded by one envelope; [onFrame] is invoked in sequence order.
     */
    private suspend inline fun collectFrom(
        startSequence: Long,
        crossinline onFrame: suspend (sequence: Long, frame: DeviceMessageFrame<DeviceMessage>) -> Unit,
    ) {
        val (snapshot, limit) = lock.withLock { offsets.toList() to fileLength }
        if (startSequence >= snapshot.size) return
        for (index in startSequence.toInt() until snapshot.size) {
            val start = snapshot[index]
            val end = if (index + 1 < snapshot.size) snapshot[index + 1] else limit
            val record = withContext(Dispatchers.IO) {
                val buffer = ByteBuffer.allocate((end - start).toInt())
                check(readFully(buffer, start)) {
                    "Journal '$path' ended mid-record at offset $start: expected ${end - start} bytes."
                }
                buffer
            }
            record.flip()
            val buffer = Buffer().apply { write(record.array(), 0, record.limit()) }
            onFrame(index.toLong(), codec.decode(format.readFrom(buffer)))
        }
    }
}
