package space.kscience.krig.core.contracts

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.kscience.krig.core.contracts.sampling.RingIntSampler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowSamplerConcurrentOrderingTest {
    @Test
    fun concurrentPublishersKeepRingAndFlowOrderAligned() = runBlocking {
        val publisherCount = 8
        val valuesPerPublisher = 20_000
        val totalValues = publisherCount * valuesPerPublisher
        val sampler = RingIntSampler(capacity = totalValues)
        val streamed = ArrayList<Int>(totalValues)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sampler.flow().take(totalValues).toList(streamed)
        }
        val start = CyclicBarrier(publisherCount + 1)
        val publishers = List(publisherCount) { publisher ->
            Thread.ofPlatform().name("sampler-publisher-$publisher").unstarted {
                start.await()
                repeat(valuesPerPublisher) { index ->
                    sampler.publishInt(publisher * valuesPerPublisher + index)
                }
            }
        }

        publishers.forEach(Thread::start)
        start.await()
        publishers.forEach(Thread::join)
        withTimeout(30_000) { collector.join() }

        assertContentEquals(sampler.snapshotIntArray().asList(), streamed)
    }

    @Test
    fun unconfinedCollectorRunsOutsideTheSamplerLock() = runBlocking {
        val sampler = RingIntSampler(capacity = 4)
        val collectorEntered = CountDownLatch(1)
        val releaseCollector = CountDownLatch(1)
        val secondPublishCompleted = CountDownLatch(1)
        val streamed = mutableListOf<Int>()
        val lockWasHeld = mutableListOf<Boolean>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            sampler.flow().take(2).collect { value ->
                streamed += value
                lockWasHeld += Thread.holdsLock(sampler.lock)
                if (value == 1) {
                    collectorEntered.countDown()
                    releaseCollector.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val firstPublisher = Thread.ofPlatform().unstarted { sampler.publishInt(1) }
        val secondPublisher = Thread.ofPlatform().unstarted {
            sampler.publishInt(2)
            secondPublishCompleted.countDown()
        }

        try {
            firstPublisher.start()
            assertTrue(collectorEntered.await(5, TimeUnit.SECONDS), "collector did not receive the first value")
            secondPublisher.start()
            assertTrue(
                secondPublishCompleted.await(5, TimeUnit.SECONDS),
                "another publisher was blocked by collector code",
            )
            assertContentEquals(listOf(1, 2), sampler.snapshotIntArray().asList())
            releaseCollector.countDown()
            firstPublisher.join()
            secondPublisher.join()
            withTimeout(5_000) { collector.join() }
            assertContentEquals(listOf(1, 2), streamed)
            assertFalse(lockWasHeld.any { it }, "collector code ran under the sampler lock")
        } finally {
            releaseCollector.countDown()
            if (firstPublisher.isAlive) firstPublisher.join()
            if (secondPublisher.isAlive) secondPublisher.join()
            collector.cancelAndJoin()
        }
    }

    @Test
    fun pendingLaneIsBoundedAndDropsTheOldestUnforwardedValues() = runBlocking {
        val sampler = RingIntSampler(capacity = 3)
        val collectorEntered = CountDownLatch(1)
        val releaseCollector = CountDownLatch(1)
        val streamed = mutableListOf<Int>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            sampler.flow().take(4).collect { value ->
                streamed += value
                if (value == 0) {
                    collectorEntered.countDown()
                    releaseCollector.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val drainingPublisher = Thread.ofPlatform().unstarted { sampler.publishInt(0) }

        try {
            drainingPublisher.start()
            assertTrue(collectorEntered.await(5, TimeUnit.SECONDS), "collector did not receive the in-flight value")
            for (value in 1..6) sampler.publishInt(value)
            assertContentEquals(listOf(4, 5, 6), sampler.snapshotIntArray().asList())

            releaseCollector.countDown()
            drainingPublisher.join()
            withTimeout(5_000) { collector.join() }
            assertContentEquals(listOf(0, 4, 5, 6), streamed)
        } finally {
            releaseCollector.countDown()
            if (drainingPublisher.isAlive) drainingPublisher.join()
            collector.cancelAndJoin()
        }
    }
}
