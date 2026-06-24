package space.kscience.krig.core.operations

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.HlcTimestamp
import kotlin.time.Duration
import kotlin.time.Clock

/**
 * Hybrid Logical Clock (Kulkarni–Demirbaş, 2014): physical time + logical counter.
 * If `A` causally precedes `B`, then `hlc(A) < hlc(B)`. Stamps outgoing messages and
 * orders incoming ones; use [kotlin.time.Clock] for device timing.
 *
 * [nodeId] identifies this clock's node; it is stamped onto every produced [HlcTimestamp] and acts
 * as the deterministic total-order tie-breaker across nodes (see [HlcTimestamp.nodeId]). The default
 * [Name.EMPTY] keeps single-node behaviour unchanged.
 */
public class HybridLogicalClock(
    private val physicalClock: Clock = Clock.System,
    private val maxRemoteFutureDrift: Duration? = null,
    private val nodeId: Name = Name.EMPTY,
) {
    private val lock = SynchronizedObject()
    private var lastPhysicalMs: Long = 0
    private var logicalCounter: Long = 0

    private fun tickAt(physicalMs: Long): HlcTimestamp {
        if (physicalMs > lastPhysicalMs) {
            lastPhysicalMs = physicalMs
            logicalCounter = 0
        } else {
            logicalCounter++
        }
        return HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
    }

    private fun mergeAt(remoteTimestamp: HlcTimestamp, physicalMs: Long): HlcTimestamp {
        val current = HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
        val maxDrift = maxRemoteFutureDrift
        if (maxDrift != null && remoteTimestamp.physicalMilliseconds > physicalMs + maxDrift.inWholeMilliseconds) {
            return tickAt(physicalMs)
        }
        val mergedPhysical = maxOf(physicalMs, current.physicalMilliseconds, remoteTimestamp.physicalMilliseconds)
        val mergedLogical: Long = when (mergedPhysical) {
            current.physicalMilliseconds ->
                if (mergedPhysical == remoteTimestamp.physicalMilliseconds) {
                    maxOf(current.logicalCounter, remoteTimestamp.logicalCounter) + 1
                } else {
                    current.logicalCounter + 1
                }
            remoteTimestamp.physicalMilliseconds -> remoteTimestamp.logicalCounter + 1
            else -> 0L
        }
        lastPhysicalMs = mergedPhysical
        logicalCounter = mergedLogical
        return HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
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
            HlcTimestamp(physicalClock.now().toEpochMilliseconds(), 0, nodeId)
        } else {
            HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
        }
    }
}
