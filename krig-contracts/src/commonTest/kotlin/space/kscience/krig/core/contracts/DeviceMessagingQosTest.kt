package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceMessagingQosTest {

    @Test
    fun fromMetaFallsBackToDefaultsForMissingKeys() {
        val messaging = DeviceMessaging.fromMeta(Meta { "dataBufferCapacity" put 1024 })
        assertEquals(1024, messaging.dataBufferCapacity)
        assertEquals(DeviceMessaging.Default.controlBufferCapacity, messaging.controlBufferCapacity)
        assertEquals(DeviceMessageDeliveryPolicy.Backpressure, messaging.controlDeliveryPolicy)
        assertEquals(DeviceMessaging.Default.dataDeliveryPolicy, messaging.dataDeliveryPolicy)
    }

    @Test
    fun fromMetaParsesDataDeliveryPolicyCaseInsensitively() {
        val messaging = DeviceMessaging.fromMeta(
            Meta {
                "dataDeliveryPolicy" put "dropoldest"
                "dataBufferCapacity" put 8
            },
        )
        assertEquals(DeviceMessageDeliveryPolicy.DropOldest, messaging.dataDeliveryPolicy)
    }

    @Test
    fun resolveReturnsDefaultWhenNoQosNodes() {
        assertEquals(DeviceMessaging.Default, DeviceMessaging.resolve(Meta.EMPTY))
    }

    @Test
    fun resolvePicksUpNamedProfile() {
        val config = Meta {
            "qos_profile" put "highRate"
            "qos_library" put {
                "highRate" put {
                    "dataDeliveryPolicy" put "DropOldest"
                    "dataBufferCapacity" put 2048
                }
            }
        }
        val messaging = DeviceMessaging.resolve(config)
        assertEquals(DeviceMessageDeliveryPolicy.DropOldest, messaging.dataDeliveryPolicy)
        assertEquals(2048, messaging.dataBufferCapacity)
    }

    @Test
    fun inlineMessagingOverridesReferencedProfile() {
        val config = Meta {
            "qos_profile" put "highRate"
            "qos_library" put {
                "highRate" put {
                    "dataDeliveryPolicy" put "DropOldest"
                    "dataBufferCapacity" put 2048
                }
            }
            "messaging" put {
                "dataBufferCapacity" put 512
            }
        }
        val messaging = DeviceMessaging.resolve(config)
        // inline wins on the overlapping key; non-overlapping profile key still applies via laminate
        assertEquals(512, messaging.dataBufferCapacity)
        assertEquals(DeviceMessageDeliveryPolicy.DropOldest, messaging.dataDeliveryPolicy)
    }
}
