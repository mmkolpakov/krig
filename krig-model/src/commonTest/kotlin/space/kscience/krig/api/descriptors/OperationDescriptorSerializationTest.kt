package space.kscience.krig.api.descriptors

import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.BehaviorAttribute
import space.kscience.krig.api.descriptors.attributes.MetadataAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.descriptors.attributes.TaskAttribute
import space.kscience.krig.api.descriptors.attributes.isLongRunningTask
import space.kscience.krig.api.descriptors.attributes.description
import space.kscience.krig.api.descriptors.attributes.metadata
import space.kscience.krig.api.descriptors.attributes.mutable
import space.kscience.krig.api.descriptors.attributes.readable
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.taskStateProperty
import space.kscience.krig.api.descriptors.attributes.timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class OperationDescriptorSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun propertyDescriptorRoundTripsWithStandardAttributes() {
        val descriptor = PropertyDescriptor(
            name = "pump.rpm".asName(),
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributesOf(
                OperationAttributeKeys.Metadata of MetadataAttribute(description = "Pump speed"),
                OperationAttributeKeys.Access of AccessAttribute(readable = true, mutable = true),
                OperationAttributeKeys.Behavior of BehaviorAttribute(
                    timeout = 250.milliseconds,
                    requiredLocks = listOf(ResourceLock("bus.rs485".asName())),
                    retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds),
                ),
            ),
        )

        val decoded = json.decodeFromString<PropertyDescriptor>(json.encodeToString(descriptor))

        assertEquals(descriptor.name, decoded.name)
        assertEquals("Pump speed", decoded.description)
        assertTrue(decoded.mutable)
        assertEquals(listOf(ResourceLock("bus.rs485".asName())), decoded.requiredLocks)
        assertEquals(2, decoded.retryPolicy?.maxAttempts)
        assertEquals(250.milliseconds, decoded.timeout)
    }

    @Test
    fun targetActualPropertyKindsRoundTrip() {
        for (kind in listOf(PropertyKind.SETPOINT, PropertyKind.MEASURED)) {
            val descriptor = PropertyDescriptor(
                name = "loop.temperature".asName(),
                kind = kind,
                valueTypeId = TypeIds.DOUBLE,
            )
            val decoded = json.decodeFromString<PropertyDescriptor>(json.encodeToString(descriptor))
            assertEquals(kind, decoded.kind)
        }

        assertTrue(json.encodeToString<PropertyKind>(PropertyKind.SETPOINT).contains("kind.setpoint"))
        assertTrue(json.encodeToString<PropertyKind>(PropertyKind.MEASURED).contains("kind.measured"))
    }

    @Test
    fun actionDescriptorRoundTripsWithAccessAttributes() {
        val descriptor = ActionDescriptor(
            name = "pump.start".asName(),
            attributes = operationAttributesOf(
                OperationAttributeKeys.Metadata of MetadataAttribute(description = "Starts the pump"),
                OperationAttributeKeys.Access of AccessAttribute(readable = true, mutable = false),
            ),
        )

        val decoded = json.decodeFromString<ActionDescriptor>(json.encodeToString(descriptor))

        assertEquals(descriptor.name, decoded.name)
        assertEquals("Starts the pump", decoded.metadata?.description)
        assertTrue(decoded.readable)
        assertEquals(false, decoded.mutable)
    }

    @Test
    fun actionDescriptorRoundTripsWithTaskAttribute() {
        val stateProperty = "calibration.state".asName()
        val descriptor = ActionDescriptor(
            name = "calibration.start".asName(),
            attributes = operationAttributesOf(
                OperationAttributeKeys.Task of TaskAttribute(
                    stateProperty = stateProperty,
                    progressProperty = "calibration.progress".asName(),
                    cancelAction = "calibration.cancel".asName(),
                ),
            ),
        )

        val decoded = json.decodeFromString<ActionDescriptor>(json.encodeToString(descriptor))

        assertTrue(decoded.isLongRunningTask)
        assertEquals(stateProperty, decoded.taskStateProperty)
    }
}
