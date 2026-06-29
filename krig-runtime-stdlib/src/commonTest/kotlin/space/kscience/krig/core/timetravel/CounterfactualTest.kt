@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.SequenceCursor
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class CounterfactualTest {

    @Test
    fun replayUntilStopsAtPredicateMatch() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 5), counterEvent(400, 4))
        val replay = CounterReplay()
        replay.replayUntil(log = log) { msg ->
            msg is PropertyChangedMessage && (msg.value.int ?: 0) >= 5
        }.let { }
        // The matching event (value=5) IS applied; events after are not.
        assertEquals(5, replay.value)
    }

    @Test
    fun counterfactualMutatesEveryEvent() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 3))
        val replay = CounterReplay()
        replay.counterfactual(
            log = log,
            at = Instant.fromEpochMilliseconds(300),
        ) { msg ->
            // +10 to every value: the "what if every reading were higher" scenario.
            val m = msg as PropertyChangedMessage
            m.copy(value = Meta(((m.value.int ?: 0) + 10).asValue()))
        }
        assertEquals(13, replay.value)
    }

    @Test
    fun branchAtAndWhatIfProducesDivergentFuture() = runTest {
        val history = listOf(counterEvent(100, 1), counterEvent(200, 2))
        val log = InMemoryEventJournal()
        history.forEach { log.record(it.testEnvelope()) }
        val replay = CounterReplay()

        val branch = replay.branchAt(log, at = Instant.fromEpochMilliseconds(200))
        assertEquals(2, replay.value) // fold advanced through history
        assertEquals(SequenceCursor(1), branch.cursor)

        // Alternative timeline: instead of continuing 2 -> 3 -> 4, we go 2 -> 100 -> 200.
        val alternative = flowOf(counterEvent(300, 100), counterEvent(400, 200))
        replay.whatIf(branch, alternative)
        assertEquals(200, replay.value)

        // Re-applying whatIf with the original continuation restores the canonical path.
        replay.whatIf(branch, flowOf(counterEvent(300, 3), counterEvent(400, 4)))
        assertEquals(4, replay.value)
    }

    @Test
    fun duplicatePropertyInjectionFailsFast() = runTest {
        val log = counterReplayLog(counterEvent(100, 1))
        val replay = CounterReplay()

        assertFailsWith<IllegalArgumentException> {
            replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(100)) {
                replace(counterEvent(100, 2))
                replace(counterEvent(100, 3))
            }
        }
    }

    @Test
    fun duplicateMutationFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            CounterfactualScope().apply {
                mutate(Instant.fromEpochMilliseconds(100), "value".asName()) { this }
                mutate(Instant.fromEpochMilliseconds(100), "value".asName()) { this }
            }
        }
    }

    @Test
    fun cursorMutationTargetsOneRecordWhenTimestampsCollide() = runTest {
        val log = InMemoryEventJournal()
        log.record(counterEvent(100, 1).testEnvelope())
        log.record(counterEvent(100, 2).testEnvelope())
        val replay = CounterReplay()

        replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(100)) {
            mutate(SequenceCursor(1), "value".asName()) {
                Meta(99.asValue())
            }
        }

        assertEquals(listOf(1, 99), replay.applied)
    }

    @Test
    fun timeWindowMutationSurvivesTimestampRounding() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(101, 2), counterEvent(200, 3))
        val replay = CounterReplay()

        replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(200)) {
            mutate(
                Instant.fromEpochMilliseconds(99)..Instant.fromEpochMilliseconds(150),
                "value".asName(),
            ) {
                Meta(99.asValue())
            }
        }

        assertEquals(listOf(99, 99, 3), replay.applied)
    }

    @Test
    fun cursorOperationsRequireCursorReplayLog() = runTest {
        val log = counterReplayLog(counterEvent(100, 1))
        val replay = CounterReplay()

        assertFailsWith<IllegalArgumentException> {
            replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(100)) {
                replace(SequenceCursor(0), counterEvent(100, 99))
            }
        }
    }
}
