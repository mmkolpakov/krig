package space.kscience.krig.api.descriptors

import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.AcquisitionPolicyAttribute
import space.kscience.krig.api.descriptors.attributes.BehaviorAttribute
import space.kscience.krig.api.descriptors.attributes.DeadbandPolicy
import space.kscience.krig.api.descriptors.attributes.DeliveryClassAttribute
import space.kscience.krig.api.descriptors.attributes.EngineeringRangeAttribute
import space.kscience.krig.api.descriptors.attributes.MessageDeliveryClass
import space.kscience.krig.api.descriptors.attributes.MetadataAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.attributes.PhysicalQuantityAttribute
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.descriptors.attributes.TaskAttribute
import space.kscience.krig.api.descriptors.attributes.VirtualPropertyAttribute
import space.kscience.krig.api.descriptors.attributes.acquisitionPolicy
import space.kscience.krig.api.descriptors.attributes.deadbandPolicy
import space.kscience.krig.api.descriptors.attributes.dependencyBindings
import space.kscience.krig.api.descriptors.attributes.isLongRunningTask
import space.kscience.krig.api.descriptors.attributes.description
import space.kscience.krig.api.descriptors.attributes.displayMax
import space.kscience.krig.api.descriptors.attributes.metadata
import space.kscience.krig.api.descriptors.attributes.messageDeliveryClass
import space.kscience.krig.api.descriptors.attributes.mutable
import space.kscience.krig.api.descriptors.attributes.physicalQuantity
import space.kscience.krig.api.descriptors.attributes.readable
import space.kscience.krig.api.descriptors.attributes.requiredLocks
import space.kscience.krig.api.descriptors.attributes.retryPolicy
import space.kscience.krig.api.descriptors.attributes.taskStateProperty
import space.kscience.krig.api.descriptors.attributes.timeout
import space.kscience.krig.api.descriptors.attributes.virtualProperty
import space.kscience.krig.api.expressions.Binary
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.expressions.Constant
import space.kscience.krig.api.expressions.NumericExpression
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

    @Test
    fun propertyDescriptorRoundTripsWithSemanticAttributes() {
        val descriptor = PropertyDescriptor(
            name = "process.pressure".asName(),
            kind = PropertyKind.MEASURED,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributesOf(
                OperationAttributeKeys.PhysicalQuantity of PhysicalQuantityAttribute(
                    quantity = "pressure".asName(),
                    dimension = "pressure".asName(),
                ),
                OperationAttributeKeys.EngineeringRange of EngineeringRangeAttribute(
                    displayMin = 0.0,
                    displayMax = 10.0,
                    alarmHigh = 8.5,
                ),
                OperationAttributeKeys.AcquisitionPolicy of AcquisitionPolicyAttribute(
                    defaultMaxRateHz = 2.0,
                    deadband = DeadbandPolicy.Absolute(0.05),
                ),
                OperationAttributeKeys.DeliveryClass of DeliveryClassAttribute(
                    messageClass = MessageDeliveryClass.CriticalTelemetry,
                ),
            ),
        )

        val decoded = json.decodeFromString<PropertyDescriptor>(json.encodeToString(descriptor))

        assertEquals("pressure".asName(), decoded.physicalQuantity?.quantity)
        assertEquals(10.0, decoded.displayMax)
        assertEquals(2.0, decoded.acquisitionPolicy?.defaultMaxRateHz)
        assertEquals(DeadbandPolicy.Absolute(0.05), decoded.deadbandPolicy)
        assertEquals(MessageDeliveryClass.CriticalTelemetry, decoded.messageDeliveryClass)
    }

    @Test
    fun physicalQuantityImpliesDefaultsAndExplicitAttributesWin() {
        val descriptor = PropertyDescriptor(
            name = "process.temperature".asName(),
            kind = PropertyKind.MEASURED,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributesOf(
                OperationAttributeKeys.PhysicalQuantity of PhysicalQuantityAttribute(
                    quantity = "temperature".asName(),
                    defaultRange = EngineeringRangeAttribute(displayMin = 0.0, displayMax = 100.0),
                    defaultAcquisition = AcquisitionPolicyAttribute(deadband = DeadbandPolicy.Absolute(0.1)),
                    defaultDelivery = DeliveryClassAttribute(MessageDeliveryClass.CriticalTelemetry),
                ),
            ),
        )

        assertEquals(100.0, descriptor.displayMax)
        assertEquals(DeadbandPolicy.Absolute(0.1), descriptor.deadbandPolicy)
        assertEquals(MessageDeliveryClass.CriticalTelemetry, descriptor.messageDeliveryClass)

        val overridden = PropertyDescriptor(
            name = "process.temperature".asName(),
            kind = PropertyKind.MEASURED,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributesOf(
                OperationAttributeKeys.PhysicalQuantity of PhysicalQuantityAttribute(
                    quantity = "temperature".asName(),
                    defaultRange = EngineeringRangeAttribute(displayMin = 0.0, displayMax = 100.0),
                    defaultAcquisition = AcquisitionPolicyAttribute(deadband = DeadbandPolicy.Absolute(0.1)),
                    defaultDelivery = DeliveryClassAttribute(MessageDeliveryClass.CriticalTelemetry),
                ),
                OperationAttributeKeys.EngineeringRange of EngineeringRangeAttribute(displayMin = -20.0, displayMax = 140.0),
                OperationAttributeKeys.AcquisitionPolicy of AcquisitionPolicyAttribute(deadband = DeadbandPolicy.Absolute(0.5)),
                OperationAttributeKeys.DeliveryClass of DeliveryClassAttribute(MessageDeliveryClass.Safety),
            ),
        )

        assertEquals(140.0, overridden.displayMax)
        assertEquals(DeadbandPolicy.Absolute(0.5), overridden.deadbandPolicy)
        assertEquals(MessageDeliveryClass.Safety, overridden.messageDeliveryClass)
    }

    @Test
    fun propertyDescriptorRoundTripsWithVirtualPropertyAttribute() {
        val rpm = Binding("drive".asName(), "rpm".asName())
        val expression: NumericExpression = Binary(
            operation = "mul",
            left = rpm,
            right = Constant(2.0),
        )
        val descriptor = PropertyDescriptor(
            name = "drive.rpm_x2".asName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributesOf(
                OperationAttributeKeys.VirtualProperty of VirtualPropertyAttribute(expression),
            ),
        )

        val decoded = json.decodeFromString<PropertyDescriptor>(json.encodeToString(descriptor))

        assertEquals(expression, decoded.virtualProperty?.expression)
        assertEquals(setOf(rpm), decoded.virtualProperty?.dependencyBindings)
    }
}
