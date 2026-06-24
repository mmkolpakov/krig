@file:Suppress("unused")
@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.benchmarks.timetravel

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.storage.journal.InMemoryEventJournal
import kotlin.time.Instant

/**
 * Replay-under-load: append a populated [InMemoryEventJournal] and measure full cursor and
 * time-window scans. Exercises the causal-ordering comparator at history scale.
 */
@State(Scope.Benchmark)
open class ReplayLoadBenchmark {
    @Param("1000", "50000")
    var events: Int = 0

    private lateinit var log: InMemoryEventJournal
    private lateinit var window: ClosedRange<Instant>

    @Setup
    open fun setup() {
        log = InMemoryEventJournal(capacity = events.coerceAtLeast(1))
        runBlocking {
            repeat(events) { index ->
                log.record(frame(index))
            }
        }
        window = Instant.fromEpochMilliseconds(0)..Instant.fromEpochMilliseconds(events.toLong())
    }

    @Benchmark
    open fun replayFromStart(blackhole: Blackhole): Int = runBlocking {
        log.replayFrom(after = null).count().also(blackhole::consume)
    }

    @Benchmark
    open fun replayTimeWindow(blackhole: Blackhole): Int = runBlocking {
        log.replay(window.start, window.endInclusive).count().also(blackhole::consume)
    }

    private fun frame(index: Int): DeviceMessageFrame<DeviceMessage> {
        val message = PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(index.toLong()),
            property = "rpm".asName(),
            value = metaOf(index.toDouble()),
            sourceDevice = "stand".asName(),
        )
        return DeviceMessageFrame(message)
    }
}
