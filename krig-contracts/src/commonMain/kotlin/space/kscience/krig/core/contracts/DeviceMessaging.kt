package space.kscience.krig.core.contracts

import kotlinx.coroutines.channels.BufferOverflow
import space.kscience.dataforge.meta.Laminate
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName

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

        /** Inline QoS node key. */
        private const val MESSAGING_NODE: String = "messaging"

        /** DDS-style named-profile reference and library keys (see [resolve]). */
        private const val QOS_PROFILE_KEY: String = "qos_profile"
        private const val QOS_LIBRARY_NODE: String = "qos_library"

        /**
         * Reads a [DeviceMessaging] from a flat QoS [Meta]; missing keys fall back to [Default].
         * `dataStrategy` is matched case-insensitively against [Strategy] names.
         */
        public fun fromMeta(meta: Meta): DeviceMessaging {
            val strategy = meta["dataStrategy"].string?.let { token ->
                Strategy.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                    ?: error("Unknown DeviceMessaging.Strategy '$token'; expected ${Strategy.entries.map { it.name }}.")
            } ?: Default.dataStrategy
            return DeviceMessaging(
                controlBufferCapacity = meta["controlBufferCapacity"].int ?: Default.controlBufferCapacity,
                dataBufferCapacity = meta["dataBufferCapacity"].int ?: Default.dataBufferCapacity,
                dataStrategy = strategy,
                replay = meta["replay"].int ?: Default.replay,
            )
        }

        /**
         * Resolves QoS from a configuration [config] cascade (DDS-style): an inline `messaging { }`
         * node overrides a named profile referenced by `qos_profile` and defined under
         * `qos_library.<name>`. Absent both, returns [Default]. QoS is read once at device creation;
         * change it through `hub.replace(...)`, not by mutating live [Meta].
         *
         * The cascade *order* is a fixed framework law, not an injection point (no `ConfigProvider`):
         * configuration is plain [Meta], and the layering `device → profile → manifest →
         * context.properties → default` is a contract of KRig, mirroring how DataForge resolves
         * `IOPlugin.workDirectory` and `Context.properties`. To source configuration from elsewhere
         * (Consul/etcd/12-factor/cloud), contribute a [Meta] layer into `context.properties` via a
         * DataForge plugin — the *source* is pluggable, the *cascade policy* is not. DI stays for
         * behaviour (services), never for reading numbers/strings.
         */
        public fun resolve(config: Meta): DeviceMessaging {
            val profile = config[QOS_PROFILE_KEY].string?.let { name -> config[QOS_LIBRARY_NODE]?.get(name.asName()) }
            val inline = config[MESSAGING_NODE]
            val effective = when {
                inline != null && profile != null -> Laminate(inline, profile)
                inline != null -> inline
                profile != null -> profile
                else -> return Default
            }
            return fromMeta(effective)
        }
    }
}
