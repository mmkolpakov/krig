@file:OptIn(space.kscience.krig.core.KrigPerformancePitfall::class)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import space.kscience.attributes.safeTypeOf
import space.kscience.krig.core.contracts.sampling.FlowSampler
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shared sampler contract: [TypedSampler.flow] streams only values published *after*
 * subscription (no replay of buffered history), regardless of whether the backing sampler is the
 * boxed [FlowSampler] or an unboxed primitive ring. This guards the regression where [FlowSampler]
 * used `replay = capacity` and silently re-delivered stale values to new collectors.
 */
class FlowSamplerContractTest {

    @Test
    fun flowSamplerDoesNotReplayValuesPublishedBeforeSubscription() = runTest {
        val sampler = FlowSampler<Int>(safeTypeOf(), capacity = 8)
        sampler.publish(1)
        sampler.publish(2)

        val collected = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            sampler.flow().take(2).toList(collected)
        }
        // Subscription is registered (UNDISPATCHED): only these post-subscription values must arrive.
        sampler.publish(3)
        sampler.publish(4)
        job.join()

        assertEquals(listOf(3, 4), collected, "new collector must not see pre-subscription values")
    }

    @Test
    fun drainOverrunCountReportsSamplesLostBetweenSnapshots() {
        val sampler = RingDoubleSampler(capacity = 4)
        assertEquals(0L, sampler.drainOverrunCount(), "no writes yet")

        repeat(4) { sampler.publishDouble(it.toDouble()) } // fills exactly, no loss
        assertEquals(0L, sampler.drainOverrunCount())

        repeat(10) { sampler.publishDouble(it.toDouble()) } // 10 writes into capacity-4 ring → 6 lost
        assertEquals(6L, sampler.drainOverrunCount())
        assertEquals(0L, sampler.drainOverrunCount(), "counter resets after draining")
    }

    @Test
    fun ringDoubleSamplerDoesNotReplayValuesPublishedBeforeSubscription() = runTest {
        val sampler = RingDoubleSampler(capacity = 8)
        sampler.publishDouble(1.0)
        sampler.publishDouble(2.0)

        val collected = mutableListOf<Double>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            sampler.flow().take(2).toList(collected)
        }
        sampler.publishDouble(3.0)
        sampler.publishDouble(4.0)
        job.join()

        assertEquals(listOf(3.0, 4.0), collected, "new collector must not see pre-subscription values")
    }

    @Test
    fun flowSamplerLatestAndSnapshotReadStoredRingWithoutSubscribers() {
        val sampler = FlowSampler<Int>(safeTypeOf(), capacity = 4)
        assertNull(sampler.latest(), "empty sampler has no latest")
        assertTrue(sampler.snapshot().isEmpty(), "empty sampler snapshot is empty")

        sampler.publish(10)
        sampler.publish(20)
        sampler.publish(30)

        assertEquals(30, sampler.latest(), "latest is the most recently published value")
        assertEquals(listOf(10, 20, 30), sampler.snapshot(), "snapshot is oldest-to-newest")
    }

    @Test
    fun flowSamplerSnapshotIsBoundedByCapacityOldestDropped() {
        val sampler = FlowSampler<Int>(safeTypeOf(), capacity = 3)
        for (v in 1..5) sampler.publish(v)

        assertEquals(listOf(3, 4, 5), sampler.snapshot(), "ring keeps the most recent capacity values")
        assertEquals(5, sampler.latest())
    }
}
