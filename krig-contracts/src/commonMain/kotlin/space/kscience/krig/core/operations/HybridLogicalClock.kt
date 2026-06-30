package space.kscience.krig.core.operations

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.krig.api.data.HlcNodeId
import space.kscience.krig.api.data.HlcTimestamp
import kotlin.time.Duration
import kotlin.time.Clock

/** Policy for interpreting local physical-clock readings before they enter [HybridLogicalClock]. */
public sealed interface HlcLocalClockPolicy {
    public fun localPhysicalMilliseconds(physicalMilliseconds: Long, lastPhysicalMilliseconds: Long): Long

    /** Trust every local physical-clock reading. */
    public data object AcceptPhysicalTime : HlcLocalClockPolicy {
        override fun localPhysicalMilliseconds(
            physicalMilliseconds: Long,
            lastPhysicalMilliseconds: Long,
        ): Long = physicalMilliseconds
    }

    /**
     * Clamp a local clock reading that jumps too far into the future and advance HLC logical time
     * instead. This keeps one bad wall-clock sample from poisoning local distributed ordering.
     */
    public data class ClampAndIncrementLogical(
        public val maxFutureDrift: Duration,
    ) : HlcLocalClockPolicy {
        init {
            require(maxFutureDrift >= Duration.ZERO) { "maxFutureDrift must not be negative." }
        }

        override fun localPhysicalMilliseconds(
            physicalMilliseconds: Long,
            lastPhysicalMilliseconds: Long,
        ): Long {
            val futureDelta = physicalMilliseconds - lastPhysicalMilliseconds
            return if (futureDelta > maxFutureDrift.inWholeMilliseconds) {
                lastPhysicalMilliseconds
            } else {
                physicalMilliseconds
            }
        }
    }
}

/**
 * Hybrid Logical Clock (Kulkarni–Demirbaş, 2014): physical time + logical counter.
 * If `A` causally precedes `B`, then `hlc(A) < hlc(B)`. Stamps outgoing messages and
 * orders incoming ones; use [kotlin.time.Clock] for device timing.
 *
 * [nodeId] identifies this clock's node; it is stamped onto every produced [HlcTimestamp] and acts
 * as the deterministic total-order tie-breaker across nodes (see [HlcTimestamp.nodeId]). The default
 * [HlcNodeId.Unspecified] keeps single-node behaviour unchanged.
 */
public class HybridLogicalClock(
    private val physicalClock: Clock = Clock.System,
    private val maxRemoteFutureDrift: Duration? = null,
    private val nodeId: HlcNodeId = HlcNodeId.Unspecified,
    private val localClockPolicy: HlcLocalClockPolicy = HlcLocalClockPolicy.AcceptPhysicalTime,
) {
    private val lock = SynchronizedObject()
    private var lastPhysicalMs: Long = 0
    private var logicalCounter: Long = 0
    private var initialized: Boolean = false

    private fun localPhysicalMs(physicalMs: Long): Long =
        if (initialized) localClockPolicy.localPhysicalMilliseconds(physicalMs, lastPhysicalMs) else physicalMs

    private fun tickAt(rawPhysicalMs: Long): HlcTimestamp {
        val physicalMs = localPhysicalMs(rawPhysicalMs)
        if (!initialized || physicalMs > lastPhysicalMs) {
            initialized = true
            lastPhysicalMs = physicalMs
            logicalCounter = 0
        } else {
            initialized = true
            logicalCounter++
        }
        return HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
    }

    private fun mergeAt(remoteTimestamp: HlcTimestamp, rawPhysicalMs: Long): HlcTimestamp {
        val physicalMs = localPhysicalMs(rawPhysicalMs)
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
        initialized = true
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
        if (!initialized) {
            HlcTimestamp(physicalClock.now().toEpochMilliseconds(), 0, nodeId)
        } else {
            HlcTimestamp(lastPhysicalMs, logicalCounter, nodeId)
        }
    }
}
