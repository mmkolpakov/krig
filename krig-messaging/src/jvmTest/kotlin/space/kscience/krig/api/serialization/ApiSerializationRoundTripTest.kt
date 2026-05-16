package space.kscience.krig.api.serialization

import kotlinx.serialization.json.Json
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceOfflineMessage
import space.kscience.krig.api.messages.DeviceOnlineMessage
import space.kscience.krig.api.identifiers.toBlueprintId
import space.kscience.krig.api.meta.MemberTag
import space.kscience.krig.api.meta.ProfileTag
import space.kscience.krig.api.addressing.Address
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Round-trips every polymorphic DTO declared in core through JSON to catch `@SerialName`
 * typos and missing subclass registrations in [krigApiSerializersModule]. Vendor-specific
 * bindings (Modbus, OPC UA, MQTT, EPICS, Tango, Protobuf) live in the protocol integration
 * modules — their round-trip tests live alongside them.
 */
class ApiSerializationRoundTripTest {

    private val json = Json {
        serializersModule = krigApiSerializersModule
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private inline fun <reified T : Any> roundTrip(value: T) {
        val encoded = json.encodeToString(value)
        val decoded = json.decodeFromString<T>(encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun metadataFeatureRoundTrip() {
        roundTrip<DeviceFeatureSpec>(
            MetadataFeature(
                tags = setOf(ProfileTag(name = "lab", version = "1.0")),
                description = "Cryostat #3",
            ),
        )
    }

    @Test
    fun profileTagRoundTrip() {
        roundTrip<MemberTag>(ProfileTag(name = "cryogenic", version = "1.0"))
    }

    @Test
    fun hubAttachedEventRoundTrip() {
        roundTrip<HubEvent>(
            HubEvent.Attached("motor.x".asName(), Instant.fromEpochMilliseconds(1), "Device"),
        )
    }

    @Test
    fun hubDetachedEventRoundTrip() {
        roundTrip<HubEvent>(
            HubEvent.Detached(
                "motor.x".asName(),
                Instant.fromEpochMilliseconds(2),
                DeviceDepartureReason.ParentClosed,
            ),
        )
    }

    @Test
    fun hubReplacedEventRoundTrip() {
        roundTrip<HubEvent>(
            HubEvent.Replaced(
                name = "motor.x".asName(),
                time = Instant.fromEpochMilliseconds(3),
                previousContractFqName = "OldDevice",
                newContractFqName = "NewDevice",
            ),
        )
    }

    @Test
    fun detachReasonGracefulRoundTrip() {
        roundTrip<DeviceDepartureReason>(DeviceDepartureReason.Graceful)
    }

    @Test
    fun detachReasonEvictedRoundTrip() {
        roundTrip<DeviceDepartureReason>(DeviceDepartureReason.Evicted)
    }

    @Test
    fun detachReasonCustomRoundTrip() {
        roundTrip<DeviceDepartureReason>(
            DeviceDepartureReason.Custom(id = "watchdog.timeout", message = "no ack in 5s"),
        )
    }

    @Test
    fun deviceOnlineMessageRoundTrip() {
        roundTrip<DeviceMessage>(
            DeviceOnlineMessage(
                time = Instant.fromEpochMilliseconds(1),
                blueprintId = "com.example.sensor".toBlueprintId(),
                sourceDevice = Address("lab".asName(), "sensor".asName()),
            ),
        )
    }

    @Test
    fun deviceOfflineMessageRoundTrip() {
        roundTrip<DeviceMessage>(
            DeviceOfflineMessage(
                time = Instant.fromEpochMilliseconds(2),
                cause = DeviceDepartureReason.Graceful,
                sourceDevice = Address("lab".asName(), "sensor".asName()),
            ),
        )
    }
}
