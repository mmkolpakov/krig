@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.ReplayLog
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

    private val source = "lab.counter".asName()

    private class CounterReplay : DeviceReconstructible<Device> {
        val applied: MutableList<Int> = mutableListOf()

        var value: Int = 0
            private set

        override suspend fun applyEvent(event: DeviceMessage) {
            val m = event as? PropertyChangedMessage ?: return
            if (m.property == "value".asName()) {
                value = m.value.int ?: value
                applied += value
            }
        }

        override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
            DeviceSnapshot(at = at, state = Meta(value.asValue()))

        override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
            value = snapshot.state.int ?: error("malformed snapshot")
        }
    }

    private fun event(t: Long, v: Int): PropertyChangedMessage = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(t),
        property = "value".asName(),
        value = Meta(v.asValue()),
        sourceDevice = source,
    )

    @Test
    fun replayUntilStopsAtPredicateMatch() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 5), event(400, 4)).testEnvelopes())
        val replay = CounterReplay()
        replay.replayUntil(log = log) { msg ->
            msg is PropertyChangedMessage && (msg.value.int ?: 0) >= 5
        }.let { }
        // The matching event (value=5) IS applied; events after are not.
        assertEquals(5, replay.value)
    }

    @Test
    fun counterfactualMutatesEveryEvent() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 3)).testEnvelopes())
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
        val history = listOf(event(100, 1), event(200, 2))
        val log = InMemoryEventJournal()
        history.forEach { log.record(it.testEnvelope()) }
        val replay = CounterReplay()

        val branch = replay.branchAt(log, at = Instant.fromEpochMilliseconds(200))
        assertEquals(2, replay.value) // fold advanced through history
        assertEquals(SequenceCursor(1), branch.cursor)

        // Alternative timeline: instead of continuing 2 -> 3 -> 4, we go 2 -> 100 -> 200.
        val alternative = flowOf(event(300, 100), event(400, 200))
        replay.whatIf(branch, alternative)
        assertEquals(200, replay.value)

        // Re-applying whatIf with the original continuation restores the canonical path.
        replay.whatIf(branch, flowOf(event(300, 3), event(400, 4)))
        assertEquals(4, replay.value)
    }

    @Test
    fun duplicatePropertyInjectionFailsFast() = runTest {
        val log = ReplayLog(flowOf(event(100, 1)).testEnvelopes())
        val replay = CounterReplay()

        assertFailsWith<IllegalArgumentException> {
            replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(100)) {
                replace(event(100, 2))
                replace(event(100, 3))
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
        log.record(event(100, 1).testEnvelope())
        log.record(event(100, 2).testEnvelope())
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
        val log = ReplayLog(flowOf(event(100, 1), event(101, 2), event(200, 3)).testEnvelopes())
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
        val log = ReplayLog(flowOf(event(100, 1)).testEnvelopes())
        val replay = CounterReplay()

        assertFailsWith<IllegalArgumentException> {
            replay.counterfactualScope(log, at = Instant.fromEpochMilliseconds(100)) {
                replace(SequenceCursor(0), event(100, 99))
            }
        }
    }
}
