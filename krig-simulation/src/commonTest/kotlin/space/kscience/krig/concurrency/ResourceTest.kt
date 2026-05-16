package space.kscience.krig.concurrency

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResourceTest {

    @Test
    fun seizeReleaseBasic() = runTest {
        val r = Resource("r", capacity = 2)
        r.seize()
        assertEquals(1, r.state.value.used)
        r.seize()
        assertEquals(2, r.state.value.used)
        r.release()
        assertEquals(1, r.state.value.used)
        r.release()
        assertEquals(0, r.state.value.used)
    }

    @Test
    fun useReleasesOnSuccess() = runTest {
        val r = Resource("r", capacity = 1)
        r.use { assertEquals(1, r.state.value.used) }
        assertEquals(0, r.state.value.used)
    }

    @Test
    fun useReleasesOnException() = runTest {
        val r = Resource("r", capacity = 1)
        val outcome = runCatching { r.use<Unit> { error("boom") } }
        assertTrue(outcome.isFailure)
        assertEquals(0, r.state.value.used)
    }

    @Test
    fun fifoOrderAtSamePriority() = runTest {
        val r = Resource("r", capacity = 1)
        r.seize()
        val order = mutableListOf<Int>()
        val a = launch { r.seize(); order += 1; r.release() }
        yield()
        val b = launch { r.seize(); order += 2; r.release() }
        yield()
        val c = launch { r.seize(); order += 3; r.release() }
        yield()
        r.release()
        a.join(); b.join(); c.join()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun higherPriorityGoesFirst() = runTest {
        val r = Resource("r", capacity = 1)
        r.seize()
        val order = mutableListOf<String>()
        val low = launch {
            r.seize(priority = ResourcePriority.Low)
            order += "low"
            r.release()
        }
        yield()
        val high = launch {
            r.seize(priority = ResourcePriority.High)
            order += "high"
            r.release()
        }
        yield()
        r.release()
        high.join(); low.join()
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun preemptLowerPriorityWaiters() = runTest {
        val r = Resource("r", capacity = 1)
        r.seize()
        val outcome = async<String> {
            try {
                r.seize(priority = ResourcePriority.Low)
                "acquired"
            } catch (e: ResourcePreemptedException) {
                "preempted:${e.resourceName}"
            }
        }
        yield()
        r.preemptWaitersBelow(ResourcePriority.Normal)
        assertEquals("preempted:r", outcome.await())
    }

    @Test
    fun cancellingGrantedWaiterReturnsCapacity() = runTest {
        val r = Resource("r", capacity = 1)
        r.seize()

        val waiter = launch {
            r.use {
                error("cancelled waiter must not enter the resource body")
            }
        }
        yield()

        r.release()
        waiter.cancel()
        runCurrent()

        assertEquals(0, r.state.value.used)
        assertEquals(0, r.state.value.waiting)
    }

    @Test
    fun cancellingGrantedWaiterWakesNextWaiter() = runTest {
        val r = Resource("r", capacity = 1)
        r.seize()

        val first = launch {
            r.use {
                error("cancelled waiter must not enter the resource body")
            }
        }
        yield()
        val secondAcquired = async {
            r.use { "second" }
        }
        yield()

        r.release()
        first.cancel()
        runCurrent()

        assertEquals("second", secondAcquired.await())
        assertEquals(0, r.state.value.used)
        assertEquals(0, r.state.value.waiting)
    }
}
