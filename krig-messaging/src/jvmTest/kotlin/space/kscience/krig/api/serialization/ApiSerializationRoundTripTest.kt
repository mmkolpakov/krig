package space.kscience.krig.api.serialization

import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.DevicePrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceOfflineMessage
import space.kscience.krig.api.messages.DeviceOnlineMessage
import space.kscience.krig.api.messages.PropertyReadResponse
import space.kscience.krig.api.messages.PropertyWriteResponse
import space.kscience.krig.api.messages.TaskStateChangedMessage
import space.kscience.krig.api.tasks.DeviceTaskId
import space.kscience.krig.api.tasks.DeviceTaskPhase
import space.kscience.krig.api.tasks.DeviceTaskProgress
import space.kscience.krig.api.tasks.DeviceTaskState
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        roundTrip<PipelineFeatureSpec>(
            MetadataFeature(
                description = "Cryostat #3",
            ),
        )
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
                previousType = "com.example.devices.MotorV1",
                newType = "com.example.devices.MotorV2",
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
                manifestId = "com.example.sensor".parseAsName(),
                sourceDevice = "lab.sensor".asName(),
            ),
        )
    }

    @Test
    fun deviceOfflineMessageRoundTrip() {
        roundTrip<DeviceMessage>(
            DeviceOfflineMessage(
                time = Instant.fromEpochMilliseconds(2),
                cause = DeviceDepartureReason.Graceful,
                sourceDevice = "lab.sensor".asName(),
            ),
        )
    }

    @Test
    fun propertyReadResponsePreservesQuality() {
        roundTrip<DeviceMessage>(
            PropertyReadResponse(
                time = Instant.fromEpochMilliseconds(3),
                property = "temperature".asName(),
                value = MetaConverter.double.convert(273.15),
                sourceDevice = "lab.sensor".asName(),
                targetDevice = "client".asName(),
                quality = DataQuality(QualitySeverity.UNCERTAIN),
            ),
        )
    }

    @Test
    fun unknownFaultTypeDegradesToGeneric() {
        val encoded = json.encodeToString<OperationFault>(ValidationFault(details = Meta.EMPTY, message = "bad range"))
        // Simulate a peer on a newer version emitting a faultType this build does not know.
        val fromFuture = encoded.replace("fault.validation", "fault.vendor.future")
        val decoded = json.decodeFromString<OperationFault>(fromFuture)
        assertIs<GenericOperationFault>(decoded)
        assertEquals("bad range", decoded.message)
    }

    @Test
    fun devicePrincipalRoundTrip() {
        roundTrip<Principal>(
            DevicePrincipal(
                verifiedIdentity = "spiffe://trust-domain/workload/pump",
                roles = setOf("device"),
            ),
        )
    }

    @Test
    fun simpleAndAnonymousPrincipalRoundTrip() {
        roundTrip<Principal>(SimplePrincipal(name = "operator", roles = setOf("admin")))
        roundTrip<Principal>(AnonymousPrincipal)
    }

    @Test
    fun propertyWriteResponsePreservesObservedQuality() {
        roundTrip<DeviceMessage>(
            PropertyWriteResponse(
                time = Instant.fromEpochMilliseconds(4),
                property = "setpoint".asName(),
                observedValue = MetaConverter.double.convert(42.0),
                sourceDevice = "lab.sensor".asName(),
                targetDevice = "client".asName(),
                observedQuality = DataQuality(QualitySeverity.BAD),
            ),
        )
    }

    @Test
    fun taskStateChangedMessageRoundTrip() {
        roundTrip<DeviceMessage>(
            TaskStateChangedMessage(
                time = Instant.fromEpochMilliseconds(5),
                task = DeviceTaskState(
                    taskId = DeviceTaskId("calibration-1"),
                    actionName = "calibration.start".asName(),
                    phase = DeviceTaskPhase.Running,
                    progress = DeviceTaskProgress(
                        fraction = 0.5,
                        step = "warmup".asName(),
                        message = "half way",
                    ),
                ),
                sourceDevice = "lab.sensor".asName(),
                targetDevice = "client".asName(),
            ),
        )
    }
}
