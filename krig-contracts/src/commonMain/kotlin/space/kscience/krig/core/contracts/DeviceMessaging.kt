package space.kscience.krig.core.contracts

import kotlinx.coroutines.channels.BufferOverflow

private const val DEFAULT_DATA_BUFFER_CAPACITY: Int = 64

/**
 * Buffer policy for the two device message planes. Control messages never drop
 * (SUSPEND). Data-plane [dataStrategy] may select SUSPEND / DROP_OLDEST / DROP_LATEST.
 */
public data class DeviceMessaging(
    val controlBufferCapacity: Int = 256,
    val dataBufferCapacity: Int = DEFAULT_DATA_BUFFER_CAPACITY,
    val dataStrategy: Strategy = Strategy.Suspend,
    val replay: Int = 0,
) {
    init {
        require(controlBufferCapacity > 0) { "controlBufferCapacity must be positive: $controlBufferCapacity" }
        require(dataBufferCapacity >= 0) { "dataBufferCapacity must be non-negative: $dataBufferCapacity" }
        require(replay >= 0) { "replay must be non-negative: $replay" }
        require(dataStrategy == Strategy.Suspend || dataBufferCapacity > 0 || replay > 0) {
            "A drop strategy requires dataBufferCapacity > 0 or replay > 0."
        }
    }

    public enum class Strategy { Suspend, DropOldest, DropLatest }

    internal fun toDataBufferOverflow(): BufferOverflow = when (dataStrategy) {
        Strategy.Suspend -> BufferOverflow.SUSPEND
        Strategy.DropOldest -> BufferOverflow.DROP_OLDEST
        Strategy.DropLatest -> BufferOverflow.DROP_LATEST
    }

    public companion object {
        /** Back-pressure default — no data loss on either plane. */
        public val Default: DeviceMessaging = DeviceMessaging()

        /** Streaming default — data plane drops oldest, control plane never drops. */
        public val Streaming: DeviceMessaging = DeviceMessaging(
            controlBufferCapacity = 256,
            dataBufferCapacity = 256,
            dataStrategy = Strategy.DropOldest,
            replay = 0,
        )

        /** Safety-critical — both planes suspend, large buffers. */
        public val SafetyCritical: DeviceMessaging = DeviceMessaging(
            controlBufferCapacity = 1024,
            dataBufferCapacity = 1024,
            dataStrategy = Strategy.Suspend,
            replay = 0,
        )
    }
}
