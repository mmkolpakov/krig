package space.kscience.krig.core.operations

import space.kscience.krig.api.data.HlcTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Manual `Clock` whose `now()` returns the script-provided sequence verbatim. Lets us
 * drive the [HybridLogicalClock] through deterministic physical-time scenarios without
 * touching wall-clock state. Each call to [now] consumes the next entry; running off
 * the end falls back to repeating the last value (so unbounded `tick()` after the
 * scripted prefix keeps producing stamps in the final ms — exactly the case the
 * "logical counter saturates within one ms" test wants to exercise).
 */
private class ScriptedClock(private val millis: List<Long>) : Clock {
    private var index = 0
    override fun now(): Instant {
        val v = millis[minOf(index, millis.lastIndex)]
        index = (index + 1).coerceAtMost(millis.size)
        return Instant.fromEpochMilliseconds(v)
    }
}

class HybridLogicalClockTest {

    @Test
    fun tickProducesIncreasingTimestampsAcrossAdvancingPhysicalTime() {
        val hlc = HybridLogicalClock(ScriptedClock(listOf(100, 200, 300)))
        val a = hlc.tick()
        val b = hlc.tick()
        val c = hlc.tick()

        assertEquals(100L, a.physicalMilliseconds)
        assertEquals(0L, a.logicalCounter)
        assertEquals(200L, b.physicalMilliseconds)
        assertEquals(0L, b.logicalCounter, "logical counter resets when physical time advances")
        assertEquals(300L, c.physicalMilliseconds)
        assertEquals(0L, c.logicalCounter)
        assertTrue(a < b)
        assertTrue(b < c)
    }

    @Test
    fun tickAdvancesLogicalCounterWhenPhysicalTimeStallsOrGoesBackwards() {
        // Same physical ms thrice, then a regression to an earlier ms — both stalls and
        // backwards-moving system clocks must be absorbed by the logical counter.
        val hlc = HybridLogicalClock(ScriptedClock(listOf(500, 500, 500, 400)))
        val t1 = hlc.tick()
        val t2 = hlc.tick()
        val t3 = hlc.tick()
        val t4 = hlc.tick()

        assertEquals(500L, t1.physicalMilliseconds); assertEquals(0L, t1.logicalCounter)
        assertEquals(500L, t2.physicalMilliseconds); assertEquals(1L, t2.logicalCounter)
        assertEquals(500L, t3.physicalMilliseconds); assertEquals(2L, t3.logicalCounter)
        // Backwards: physical drops to 400, but HLC clamps to lastPhysicalMs (500) and bumps logical.
        assertEquals(500L, t4.physicalMilliseconds)
        assertEquals(3L, t4.logicalCounter)
        assertTrue(t1 < t2 && t2 < t3 && t3 < t4, "ticks must remain monotonically increasing")
    }

    @Test
    fun updateMergesRemoteTimestampWithFutureRemotePhysicalAdvancingLocal() {
        // Local at ms=100; remote arrives stamped at ms=500 — local must catch up.
        val hlc = HybridLogicalClock(ScriptedClock(listOf(100, 100)))
        hlc.tick().let { } // primes lastPhysicalMs=100, logicalCounter=0

        val remote = HlcTimestamp(physicalMilliseconds = 500, logicalCounter = 7)
        val merged = hlc.update(remote)

        assertEquals(500L, merged.physicalMilliseconds)
        assertEquals(8L, merged.logicalCounter, "merge bumps remote logical by 1 when remote dominates physical")
    }

    @Test
    fun updateRejectsRemoteTimestampBeyondConfiguredFutureDrift() {
        val hlc = HybridLogicalClock(
            physicalClock = ScriptedClock(listOf(1_000, 1_000, 1_000)),
            maxRemoteFutureDrift = 100.milliseconds,
        )
        hlc.tick().let { } // local=(1000,0)

        val merged = hlc.update(HlcTimestamp(physicalMilliseconds = 10_000, logicalCounter = 7))

        assertEquals(1_000L, merged.physicalMilliseconds)
        assertEquals(1L, merged.logicalCounter, "far-future remote stamps should not poison local HLC")
    }

    @Test
    fun updateBumpsLogicalWhenLocalAndRemoteSharePhysical() {
        val hlc = HybridLogicalClock(ScriptedClock(listOf(100, 100, 100)))
        hlc.tick().let { } // lastPhysicalMs=100, logical=0
        hlc.tick().let { } // logical=1
        val remote = HlcTimestamp(physicalMilliseconds = 100, logicalCounter = 5)
        val merged = hlc.update(remote)
        assertEquals(100L, merged.physicalMilliseconds)
        // max(localLogical=1, remoteLogical=5) + 1 = 6
        assertEquals(6L, merged.logicalCounter)
    }

    @Test
    fun updateUsesPhysicalNowWhenItDominatesBothSides() {
        val hlc = HybridLogicalClock(ScriptedClock(listOf(100, 100, 1000)))
        hlc.tick().let { } // local=(100,0)
        hlc.tick().let { } // local=(100,1)
        val remote = HlcTimestamp(physicalMilliseconds = 200, logicalCounter = 9)
        val merged = hlc.update(remote)
        assertEquals(1000L, merged.physicalMilliseconds, "physical now (1000) > both local (100) and remote (200)")
        assertEquals(0L, merged.logicalCounter, "fresh ms — logical counter resets")
    }

    @Test
    fun snapshotDoesNotAdvanceClock() {
        val hlc = HybridLogicalClock(ScriptedClock(listOf(100, 100, 100)))
        hlc.tick().let { } // (100, 0)
        val snap1 = hlc.snapshot()
        val snap2 = hlc.snapshot()
        assertEquals(snap1, snap2, "snapshot must be idempotent — no logical bump")
        // Next tick still on ms=100 should produce logical=1 (only one tick has happened).
        val next = hlc.tick()
        assertEquals(100L, next.physicalMilliseconds)
        assertEquals(1L, next.logicalCounter)
    }

    @Test
    fun snapshotBeforeAnyTickReturnsCurrentPhysicalTime() {
        // No tick(), no update() — snapshot must still return a valid stamp at the current
        // physical clock reading, with logical=0.
        val hlc = HybridLogicalClock(ScriptedClock(listOf(777)))
        val snap = hlc.snapshot()
        assertEquals(777L, snap.physicalMilliseconds)
        assertEquals(0L, snap.logicalCounter)
    }
}
