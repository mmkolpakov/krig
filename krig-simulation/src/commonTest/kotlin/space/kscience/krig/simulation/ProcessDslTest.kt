package space.kscience.krig.simulation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import space.kscience.krig.concurrency.Resource
import space.kscience.krig.concurrency.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessDslTest {

    @Test
    fun holdAdvancesVirtualTime() = runTest {
        val log = mutableListOf<Long>()
        process("holder") {
            log += testScheduler.currentTime
            hold(100.milliseconds)
            log += testScheduler.currentTime
            hold(200.milliseconds)
            log += testScheduler.currentTime
        }.join()
        assertEquals(listOf(0L, 100L, 300L), log)
    }

    @Test
    fun waitUntilResumesOnSignalMatch() = runTest {
        val signal = Signal(0)
        val result = mutableListOf<Int>()
        val waiter = process("waiter") {
            val v = waitUntil(signal) { it >= 3 }
            result += v
        }
        signal.set(1); signal.set(2); signal.set(3)
        waiter.join()
        assertEquals(listOf(3), result)
    }

    @Test
    fun requestAcquiresAndReleases() = runTest {
        val r = Resource("r", capacity = 1)
        val order = mutableListOf<String>()
        val a = process("a") {
            request(r) {
                order += "a-in"
                hold(50.milliseconds)
                order += "a-out"
            }
        }
        val b = process("b") {
            request(r) {
                order += "b-in"
                order += "b-out"
            }
        }
        joinAll(a, b)
        // a acquires first (FIFO), runs 50ms, releases; then b
        assertEquals(listOf("a-in", "a-out", "b-in", "b-out"), order)
    }

    @Test
    fun holdsAreDeterministicAcrossConcurrentProcesses() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val fast = process("fast") {
            hold(10.milliseconds)
            log += "fast" to testScheduler.currentTime
        }
        val slow = process("slow") {
            hold(20.milliseconds)
            log += "slow" to testScheduler.currentTime
        }
        joinAll(fast, slow)
        assertEquals(listOf("fast" to 10L, "slow" to 20L), log)
    }
}
