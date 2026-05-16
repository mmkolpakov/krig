package space.kscience.krig.core.operations

import kotlinx.coroutines.channels.BufferOverflow
import space.kscience.krig.api.messages.DeviceMessage

/**
 * Backpressure policy for the Data-Plane publisher. Wraps [BufferOverflow] from
 * `kotlinx.coroutines` with an optional custom handler for callers who cannot
 * tolerate silent drops.
 */
public sealed interface OverflowPolicy {
    /** Maps to one of the three kotlinx-coroutines overflow modes. */
    public data class Basic(public val kotlinx: BufferOverflow) : OverflowPolicy

    /**
     * Custom handler invoked per overflow event. Must be non-suspending in hot
     * loops if the caller can't tolerate blocking.
     */
    public fun interface Custom : OverflowPolicy {
        public suspend fun handle(message: DeviceMessage)
    }

    public companion object {
        /** Producer suspends until the consumer catches up. */
        public val Suspend: OverflowPolicy = Basic(BufferOverflow.SUSPEND)

        /** Oldest buffered message is discarded when the buffer is full. */
        public val DropOldest: OverflowPolicy = Basic(BufferOverflow.DROP_OLDEST)

        /** Newest message is discarded. */
        public val DropLatest: OverflowPolicy = Basic(BufferOverflow.DROP_LATEST)
    }
}
