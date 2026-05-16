package space.kscience.krig.core.operations

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Clock

/**
 * Hybrid Logical Clock (Kulkarni–Demirbaş, 2014): physical time + logical counter.
 * If `A` causally precedes `B`, then `hlc(A) < hlc(B)`. Stamps outgoing messages and
 * orders incoming ones; use [kotlin.time.Clock] for device timing.
 */
public class HybridLogicalClock(
    private val physicalClock: Clock = Clock.System,
    private val maxRemoteFutureDrift: Duration? = null,
) {
    private val lock = SynchronizedObject()
    private var lastPhysicalMs: Long = 0
    private var logicalCounter: Int = 0

    private fun tickAt(physicalMs: Long): HlcTimestamp {
        if (physicalMs > lastPhysicalMs) {
            lastPhysicalMs = physicalMs
            logicalCounter = 0
        } else {
            logicalCounter++
        }
        return HlcTimestamp(lastPhysicalMs, logicalCounter)
    }

    private fun mergeAt(remoteTimestamp: HlcTimestamp, physicalMs: Long): HlcTimestamp {
        val current = HlcTimestamp(lastPhysicalMs, logicalCounter)
        val maxDrift = maxRemoteFutureDrift
        if (maxDrift != null && remoteTimestamp.physicalMilliseconds > physicalMs + maxDrift.inWholeMilliseconds) {
            return tickAt(physicalMs)
        }
        val mergedPhysical = maxOf(physicalMs, current.physicalMilliseconds, remoteTimestamp.physicalMilliseconds)
        val mergedLogical = when {
            mergedPhysical == current.physicalMilliseconds && mergedPhysical == remoteTimestamp.physicalMilliseconds ->
                maxOf(current.logicalCounter, remoteTimestamp.logicalCounter) + 1
            mergedPhysical == current.physicalMilliseconds -> current.logicalCounter + 1
            mergedPhysical == remoteTimestamp.physicalMilliseconds -> remoteTimestamp.logicalCounter + 1
            else -> 0
        }
        lastPhysicalMs = mergedPhysical
        logicalCounter = mergedLogical
        return HlcTimestamp(lastPhysicalMs, logicalCounter)
    }

    /** Next local timestamp. Advances the logical counter within the same physical ms. */
    public fun tick(): HlcTimestamp = synchronized(lock) {
        tickAt(physicalClock.now().toEpochMilliseconds())
    }

    /** Merges [remoteTimestamp] into the local clock and returns the updated value. */
    public fun update(remoteTimestamp: HlcTimestamp): HlcTimestamp = synchronized(lock) {
        mergeAt(remoteTimestamp, physicalClock.now().toEpochMilliseconds())
    }

    /** Current snapshot without advancing the clock. */
    public fun snapshot(): HlcTimestamp = synchronized(lock) {
        if (lastPhysicalMs == 0L) {
            HlcTimestamp(physicalClock.now().toEpochMilliseconds(), 0)
        } else {
            HlcTimestamp(lastPhysicalMs, logicalCounter)
        }
    }
}
