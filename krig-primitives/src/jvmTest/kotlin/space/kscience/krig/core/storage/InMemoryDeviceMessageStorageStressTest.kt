@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.core.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.set
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Stress checks the bounded replay side of [InMemoryDeviceMessageStorage].
 *
 * The live tail is an observability stream and is emitted outside the storage lock,
 * so replay order is the durable contract here; callers that need an exact append log
 * should use a persistent backend.
 */
class InMemoryDeviceMessageStorageStressTest {

    private fun makeMsg(seq: Int): DeviceMessage = PropertyChangedMessage(
        time = Clock.System.now(),
        property = "p".asName(),
        value = Meta { set("seq", seq) },
        sourceDevice = Address(route = Name.EMPTY, device = "d".asName()),
    )

    private fun seqOf(m: DeviceMessage): Int =
        ((m as PropertyChangedMessage).value["seq"]?.int) ?: error("missing seq")

    @Test
    fun replayRetainsEveryConcurrentWriteWhenCapacityAllows() = runBlocking(Dispatchers.Default) {
        val writers = 8
        val perWriter = 200
        val total = writers * perWriter
        val storage = InMemoryDeviceMessageStorage(capacity = total)

        coroutineScope {
            val jobs = List(writers) { w ->
                launch {
                    repeat(perWriter) { i ->
                        storage.write(makeMsg(w * perWriter + i))
                    }
                }
            }
            jobs.joinAll()

            val replay = withTimeout(5.seconds) { storage.readAll().toList() }

            assertEquals(total, replay.size)
            assertEquals(
                (0 until total).toSet(),
                replay.map(::seqOf).toSet(),
                "replay must retain every write when capacity is large enough",
            )
        }
    }

    @Test
    fun replayEvictsOldestWritesWhenCapacityIsExceeded() = runBlocking(Dispatchers.Default) {
        val storage = InMemoryDeviceMessageStorage(capacity = 3)

        repeat(5) { storage.write(makeMsg(it)) }

        val replay = withTimeout(5.seconds) { storage.readAll().toList() }
        assertEquals(listOf(2, 3, 4), replay.map(::seqOf))
    }

    @Test
    fun writeAllPreservesBatchOrderInReplay() = runBlocking(Dispatchers.Default) {
        val batches = 30
        val perBatch = 20
        val total = batches * perBatch
        val storage = InMemoryDeviceMessageStorage(capacity = total)

        coroutineScope {
            val jobs = List(batches) { b ->
                launch {
                    val batch = List(perBatch) { i -> makeMsg(b * perBatch + i) }
                    storage.writeAll(batch)
                }
            }
            jobs.joinAll()

            val replay = withTimeout(5.seconds) { storage.readAll().toList() }

            assertEquals(total, replay.size)

            // Inside each batch (consecutive seq numbers from one writer) the items must
            // stay contiguous: writeAll is one unit of work per batch.
            val grouped = replay.map(::seqOf).chunked(perBatch)
            grouped.forEach { chunk ->
                val writer = chunk.first() / perBatch
                val expected = (writer * perBatch until (writer + 1) * perBatch).toList()
                assertEquals(expected, chunk, "batch from writer $writer must stay contiguous")
            }
        }
    }
}
