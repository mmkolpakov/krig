@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.SequenceCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TimeTravelSessionTest {

    private val source = "lab.counter".asName()

    private class CounterReplay : DeviceReconstructible<Device> {
        var value: Int = 0
            private set

        override suspend fun applyEvent(event: DeviceMessage) {
            val m = event as? PropertyChangedMessage ?: return
            if (m.property == "value".asName()) value = m.value.int ?: value
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

    private suspend fun session(model: CounterReplay): Pair<TimeTravelSession, InMemoryEventJournal> {
        val log = InMemoryEventJournal()
        listOf(event(100, 1), event(200, 2), event(300, 3)).forEach { log.record(it.testEnvelope()) }
        val session = TimeTravelSession(
            model = model,
            log = log,
            snapshotStore = InMemorySnapshotStore(),
            deviceName = source,
            snapshotCodec = SnapshotCodec(),
        )
        return session to log
    }

    @Test
    fun seekReconstructsStateAtInstant() = runTest {
        val model = CounterReplay()
        val (session, _) = session(model)
        session.seek(Instant.fromEpochMilliseconds(250))
        assertEquals(2, model.value)
    }

    @Test
    fun replayUntilStopsAtPredicateThroughSession() = runTest {
        val model = CounterReplay()
        val (session, _) = session(model)
        val matched = session.replayUntil { it is PropertyChangedMessage && (it.value.int ?: 0) >= 2 }
        assertEquals(2, (matched as PropertyChangedMessage).value.int)
        assertEquals(2, model.value)
    }

    @Test
    fun counterfactualMutatesThroughSession() = runTest {
        val model = CounterReplay()
        val (session, _) = session(model)
        session.counterfactual(Instant.fromEpochMilliseconds(300)) { msg ->
            val m = msg as PropertyChangedMessage
            m.copy(value = Meta(((m.value.int ?: 0) + 10).asValue()))
        }
        assertEquals(13, model.value)
    }

    @Test
    fun branchAtAndWhatIfThroughSession() = runTest {
        val model = CounterReplay()
        val (session, _) = session(model)
        val branch = session.branchAt(Instant.fromEpochMilliseconds(200))
        assertEquals(2, model.value)
        assertEquals(SequenceCursor(1), branch.cursor)

        session.whatIf(branch, flowOf(event(400, 100), event(500, 200)))
        assertEquals(200, model.value)
    }
}
