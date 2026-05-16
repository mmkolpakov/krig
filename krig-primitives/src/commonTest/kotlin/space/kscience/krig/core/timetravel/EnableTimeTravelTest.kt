@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    ExperimentalTimeTravelApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package space.kscience.krig.core.timetravel

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Conformance test for [enableTimeTravel] � verifies that the assembly actually
 * wires the configured message flow into [DeviceEventSink] and drives `runCheckpointing` under
 * a single cancelable [kotlinx.coroutines.Job]. Regressions that silently drop
 * events or leak coroutines break here.
 */
class EnableTimeTravelTest {

    private val deviceAddr = Address(route = "lab".asName(), device = "counter".asName())

    @OptIn(space.kscience.krig.core.InternalKrigApi::class)
    private class ReplayingDevice(name: Name, context: Context) :
        AbstractDevice(name, DeviceRuntime(context)) {
        override suspend fun readProperty(propertyName: Name): Meta =
            error("Not used in test")
        override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
        override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
    }

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
        sourceDevice = deviceAddr,
        property = "value".asName(),
        value = Meta(v.asValue()),
    )

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private fun testMessages(): MutableSharedFlow<DeviceMessage> =
        MutableSharedFlow(extraBufferCapacity = 16)

    /**
     * Build a scope rooted on the test dispatcher so `advanceUntilIdle()` actually drives
     * the pump / checkpointer. `device.deviceScope` defaults to a Context-owned scope that
     * is not the test dispatcher � passing it to [enableTimeTravel] would launch the
     * children on a real dispatcher and race with publication from the test thread.
     */
    private suspend fun testScope(): CoroutineScope {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
            ?: error("Test scope must provide a dispatcher")
        return CoroutineScope(Job() + dispatcher)
    }

    @Test
    fun eventPumpForwardsControlFlowToSink() = runTest {
        val device = ReplayingDevice("counter".asName(), Context("test-event-pump"))
        val eventSink = InMemoryEventLogStore()
        val snapshotStore = InMemorySnapshotStore()
        val replay = CounterReplay()
        val messages = testMessages()

        val job = device.enableTimeTravel(
            reconstructible = replay,
            deviceName = "counter".asName(),
            snapshotStore = snapshotStore,
            eventSink = eventSink,
            strategy = CheckpointStrategy.Manual,
            messageFlow = messages,
            scope = testScope(),
            clock = FixedClock(Instant.fromEpochMilliseconds(1000)),
        )
        advanceUntilIdle()  // let the pump coroutine subscribe before the first emit

        // With BufferOverflow.SUSPEND on MutableSharedFlow and no replay, sequential
        // emits can race each other if the pump's channelFlow-backed merge is still
        // draining an earlier message. advanceUntilIdle between emits keeps the virtual
        // clock-driven test deterministic.
        messages.emit(event(100, 1))
        advanceUntilIdle()
        messages.emit(event(200, 2))
        advanceUntilIdle()
        messages.emit(event(300, 3))
        advanceUntilIdle()

        assertEquals(3, eventSink.size())

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun checkpointingEveryNEventsWritesSnapshotsToStore() = runTest {
        val device = ReplayingDevice("counter".asName(), Context("test-checkpoint-n"))
        val eventSink = InMemoryEventLogStore()
        val snapshotStore = InMemorySnapshotStore()
        val replay = CounterReplay()
        val messages = testMessages()

        val job = device.enableTimeTravel(
            reconstructible = replay,
            deviceName = "counter".asName(),
            snapshotStore = snapshotStore,
            eventSink = eventSink,
            strategy = CheckpointStrategy.EveryNEvents(2),
            messageFlow = messages,
            scope = testScope(),
            clock = FixedClock(Instant.fromEpochMilliseconds(1000)),
        )
        advanceUntilIdle()  // let the pump coroutine subscribe before the first emit

        // Drive the replay state via event publication. CounterReplay folds each event
        // into its `value`; captureSnapshot reads that value off the fold.
        replay.applyEvent(event(100, 5))
        messages.emit(event(100, 5))
        replay.applyEvent(event(200, 7))
        messages.emit(event(200, 7))
        advanceUntilIdle()

        // EveryNEvents(2) fires once after two events; the pump and the checkpointer
        // both observe the same shared controlFlow.
        val snapshot = snapshotStore.latestBefore("counter".asName(), Instant.fromEpochMilliseconds(10_000))
        assertNotNull(snapshot)
        assertEquals(7, snapshot.state.int)

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun cancellingReturnedJobStopsBothChildren() = runTest {
        val device = ReplayingDevice("counter".asName(), Context("test-cancel"))
        val eventSink = InMemoryEventLogStore()
        val snapshotStore = InMemorySnapshotStore()
        val replay = CounterReplay()
        val messages = testMessages()

        val job = device.enableTimeTravel(
            reconstructible = replay,
            deviceName = "counter".asName(),
            snapshotStore = snapshotStore,
            eventSink = eventSink,
            strategy = CheckpointStrategy.EveryNEvents(1),
            messageFlow = messages,
            scope = testScope(),
            clock = FixedClock(Instant.fromEpochMilliseconds(1000)),
        )
        advanceUntilIdle()  // let the pump coroutine subscribe before the first emit

        messages.emit(event(100, 1))
        advanceUntilIdle()
        assertEquals(1, eventSink.size())

        job.cancel()
        advanceUntilIdle()

        // Post-cancel events must not reach the sink.
        messages.emit(event(200, 2))
        advanceUntilIdle()
        assertEquals(1, eventSink.size())
        assertTrue(job.isCancelled)
    }

    @Test
    fun convenienceOverloadSpinsUpInMemoryEventLog() = runTest {
        val device = ReplayingDevice("counter".asName(), Context("test-convenience"))
        val snapshotStore = InMemorySnapshotStore()
        val replay = CounterReplay()
        val messages = testMessages()

        val (job, eventLog) = device.enableTimeTravel(
            reconstructible = replay,
            deviceName = "counter".asName(),
            snapshotStore = snapshotStore,
            strategy = CheckpointStrategy.Manual,
            messageFlow = messages,
            scope = testScope(),
            clock = FixedClock(Instant.fromEpochMilliseconds(1000)),
        )
        advanceUntilIdle()  // let the pump coroutine subscribe before the first emit

        messages.emit(event(100, 1))
        advanceUntilIdle()
        // assertIs carries the contract `returns() implies (value is T)`, so the
        // subsequent `eventLog.size()` smart-casts through without an explicit `as`.
        assertIs<InMemoryEventLogStore>(eventLog)
        assertEquals(1, eventLog.size())

        job.cancel()
        advanceUntilIdle()
    }
}
