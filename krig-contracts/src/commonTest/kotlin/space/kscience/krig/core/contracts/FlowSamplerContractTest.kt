@file:OptIn(space.kscience.krig.core.KrigPerformancePitfall::class)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import space.kscience.attributes.safeTypeOf
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.core.contracts.sampling.FlowSampler
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.RingIntSampler
import space.kscience.krig.core.contracts.sampling.RingLongSampler
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        assertHotNonReplay(
            flow = sampler.flow(),
            beforeSubscription = listOf(1, 2),
            afterSubscription = listOf(3, 4),
            publish = sampler::publish,
        )
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
    fun primitiveRingSamplersDoNotReplayValuesPublishedBeforeSubscription() = runTest {
        val doubles = RingDoubleSampler(capacity = 8)
        assertHotNonReplay(
            flow = doubles.flow(),
            beforeSubscription = listOf(1.0, 2.0),
            afterSubscription = listOf(3.0, 4.0),
            publish = doubles::publishDouble,
        )

        val ints = RingIntSampler(capacity = 8)
        assertHotNonReplay(
            flow = ints.flow(),
            beforeSubscription = listOf(1, 2),
            afterSubscription = listOf(3, 4),
            publish = ints::publishInt,
        )

        val longs = RingLongSampler(capacity = 8)
        assertHotNonReplay(
            flow = longs.flow(),
            beforeSubscription = listOf(1L, 2L),
            afterSubscription = listOf(3L, 4L),
            publish = longs::publishLong,
        )
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

    @Test
    fun primitiveRingSamplersWrapAndExposeLatestWithoutSubscribers() {
        val doubles = RingDoubleSampler(capacity = 3)
        assertTrue(doubles.latestDoubleOrNaN().isNaN())
        for (value in 1..5) doubles.publishDouble(value.toDouble())
        assertContentEquals(doubleArrayOf(3.0, 4.0, 5.0), doubles.snapshotDoubleArray())
        assertEquals(5.0, doubles.latestDoubleOrNaN())
        assertEquals(5.0, doubles.latest())

        val ints = RingIntSampler(capacity = 3)
        assertEquals(-1, ints.latestIntOr(-1))
        for (value in 1..5) ints.publishInt(value)
        assertContentEquals(intArrayOf(3, 4, 5), ints.snapshotIntArray())
        assertEquals(5, ints.latestIntOr(-1))
        assertEquals(5, ints.latest())

        val longs = RingLongSampler(capacity = 3)
        assertEquals(-1L, longs.latestLongOr(-1L))
        for (value in 1L..5L) longs.publishLong(value)
        assertContentEquals(longArrayOf(3L, 4L, 5L), longs.snapshotLongArray())
        assertEquals(5L, longs.latestLongOr(-1L))
        assertEquals(5L, longs.latest())
    }

    @Test
    fun qualityRanksStayAlignedForRepresentableValuesWhenRingWraps() {
        val sampler = FlowSampler<String>(safeTypeOf(), capacity = 3, trackQuality = true)
        assertTrue(sampler.tracksQuality)
        assertNull(sampler.latestSeverity())
        assertContentEquals(intArrayOf(), sampler.snapshotSeverityRanks())

        sampler.publish("dropped", QualitySeverity.GOOD)
        sampler.publish("uncertain", QualitySeverity.UNCERTAIN)
        sampler.publish("bad", QualitySeverity.BAD)
        sampler.publish("custom", QualitySeverity(255))

        assertEquals(listOf("uncertain", "bad", "custom"), sampler.snapshot())
        assertContentEquals(intArrayOf(50, 100, 255), sampler.snapshotSeverityRanks())
        assertEquals(QualitySeverity(255), sampler.latestSeverity())
    }

    @Test
    fun qualityRanksPreserveOpenIntScaleWhenRingWraps() {
        val sampler = FlowSampler<String>(safeTypeOf(), capacity = 3, trackQuality = true)

        sampler.publish("dropped", QualitySeverity.GOOD)
        sampler.publish("negative", QualitySeverity(-1))
        sampler.publish("above-byte", QualitySeverity(256))
        sampler.publish("custom", QualitySeverity(300))

        assertEquals(listOf("negative", "above-byte", "custom"), sampler.snapshot())
        assertContentEquals(intArrayOf(-1, 256, 300), sampler.snapshotSeverityRanks())
        assertEquals(QualitySeverity(300), sampler.latestSeverity())
    }

    @Test
    fun typedQualityEntryPointsPublishAndExposeLatestSeverity() {
        val generic = FlowSampler<String>(safeTypeOf(), capacity = 1, trackQuality = true)
        generic.publish("value", QualitySeverity(-1))
        assertEquals(QualitySeverity(-1), generic.latestSeverity())

        val doubles = RingDoubleSampler(capacity = 1, trackQuality = true)
        doubles.publish(1.0, QualitySeverity(256))
        assertEquals(QualitySeverity(256), doubles.latestSeverity())

        val ints = RingIntSampler(capacity = 1, trackQuality = true)
        ints.publish(1, QualitySeverity(300))
        assertEquals(QualitySeverity(300), ints.latestSeverity())

        val longs = RingLongSampler(capacity = 1, trackQuality = true)
        longs.publish(1L, QualitySeverity(Int.MAX_VALUE))
        assertEquals(QualitySeverity(Int.MAX_VALUE), longs.latestSeverity())
    }

    @Test
    fun primitiveQualityEntryPointsPreserveIntBoundaries() {
        val doubles = RingDoubleSampler(capacity = 2, trackQuality = true)
        doubles.publish(1.0, QualitySeverity(Int.MIN_VALUE))
        doubles.publish(2.0, QualitySeverity(Int.MAX_VALUE))
        assertContentEquals(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), doubles.snapshotSeverityRanks())
        assertEquals(QualitySeverity(Int.MAX_VALUE), doubles.latestSeverity())

        val ints = RingIntSampler(capacity = 2, trackQuality = true)
        ints.publish(1, QualitySeverity(Int.MIN_VALUE))
        ints.publish(2, QualitySeverity(Int.MAX_VALUE))
        assertContentEquals(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), ints.snapshotSeverityRanks())
        assertEquals(QualitySeverity(Int.MAX_VALUE), ints.latestSeverity())

        val longs = RingLongSampler(capacity = 2, trackQuality = true)
        longs.publish(1L, QualitySeverity(Int.MIN_VALUE))
        longs.publish(2L, QualitySeverity(Int.MAX_VALUE))
        assertContentEquals(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), longs.snapshotSeverityRanks())
        assertEquals(QualitySeverity(Int.MAX_VALUE), longs.latestSeverity())
    }

    private suspend fun <T> CoroutineScope.assertHotNonReplay(
        flow: Flow<T>,
        beforeSubscription: List<T>,
        afterSubscription: List<T>,
        publish: (T) -> Unit,
    ) {
        beforeSubscription.forEach(publish)
        val collected = mutableListOf<T>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            flow.take(afterSubscription.size).toList(collected)
        }
        afterSubscription.forEach(publish)
        job.join()

        assertEquals(afterSubscription, collected, "new collector must not see pre-subscription values")
    }
}
