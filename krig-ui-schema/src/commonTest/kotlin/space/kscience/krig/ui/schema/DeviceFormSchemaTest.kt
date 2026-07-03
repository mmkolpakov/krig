package space.kscience.krig.ui.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.of
import space.kscience.krig.api.descriptors.operationAttributesOf
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.AcquisitionPolicyAttribute
import space.kscience.krig.api.descriptors.attributes.DeliveryClassAttribute
import space.kscience.krig.api.descriptors.attributes.EngineeringRangeAttribute
import space.kscience.krig.api.descriptors.attributes.MessageDeliveryClass
import space.kscience.krig.api.descriptors.attributes.MetadataAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.attributes.TaskAttribute
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.DynamicDiscoveryPolicy
import space.kscience.krig.core.contracts.manifestOf
import kotlin.time.Instant

class DeviceFormSchemaTest {

    @Test
    fun manifestProjectionCarriesContractFields() {
        val schema = pumpManifest().toDeviceFormSchema()

        assertEquals(DEVICE_FORM_SCHEMA_VERSION, schema.schemaVersion)
        assertEquals("demo.pump".parseAsName(), schema.manifestId)
        assertEquals("1.2.3", schema.manifestVersion)
        assertEquals("demo.Pump", schema.deviceContractFqName)
        assertTrue(schema.schemaHash.startsWith("fnv1a64:"))
        assertEquals(listOf("rpm", "temperature"), schema.properties.map { it.name.toString() })
        assertTrue(DeviceFormCapability.Read in schema.rendererProfile.capabilities)
        assertTrue(DeviceFormCapability.Write in schema.rendererProfile.capabilities)
        assertTrue(DeviceFormCapability.Execute in schema.rendererProfile.capabilities)

        val rpm = schema.properties.first { it.name == "rpm".asName() }
        assertEquals("property.rpm".parseAsName(), rpm.id.name)
        assertEquals(PropertyKind.MEASURED, rpm.kind)
        assertEquals(TypeIds.DOUBLE, rpm.valueTypeId)
        assertTrue(rpm.readable)
        assertTrue(rpm.mutable)
        assertEquals("Shaft speed", rpm.description)
        assertEquals("rpm", rpm.unit)
        assertEquals(DeviceFormPropertyOrigin.Manifest, rpm.origin)
        assertEquals(MetaDescriptor(), rpm.valueDescriptor)
        assertEquals(DeviceFormControlKind.NumericInput, rpm.controlKind)
        assertNotNull(rpm.valueBinding)
        assertNotNull(rpm.qualityBinding)
        assertEquals(10.0, rpm.semantics.engineeringRange?.displayMin)
        assertEquals(MessageDeliveryClass.CriticalTelemetry, rpm.semantics.deliveryClass?.messageClass)

        val action = schema.actions.single()
        assertEquals("action.reset".parseAsName(), action.id.name)
        assertEquals("reset".asName(), action.name)
        assertEquals("Reset command", action.description)
        assertEquals(DeviceFormCommandKind.ExecuteAction, action.command.kind)
        assertNull(action.task)
    }

    @Test
    fun discoveredPropertiesStayOutsideManifestProperties() {
        val discovered = PropertyDescriptor(
            name = "debug.raw".parseAsName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.META,
        )

        val schema = pumpManifest().toDeviceFormSchema(
            discoveredProperties = listOf(discovered),
            dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.Learn,
        )

        assertEquals(listOf("rpm", "temperature"), schema.properties.map { it.name.toString() })
        assertEquals(listOf("debug.raw"), schema.discoveredProperties.map { it.name.toString() })
        assertEquals(DeviceFormPropertyOrigin.Discovered, schema.discoveredProperties.single().origin)
        assertEquals(DynamicDiscoveryPolicy.Learn, schema.dynamicDiscoveryPolicy)
        assertTrue(DeviceFormCapability.DynamicDiscovery in schema.rendererProfile.capabilities)
        assertTrue(DeviceFormRenderHint.Dynamic in schema.discoveredProperties.single().renderHints)
        assertEquals(listOf("properties", "discovered", "actions"), schema.sections.map { it.label })
    }

    @Test
    fun actionsExposeTaskSemanticsWithoutDescriptorPollution() {
        val schema = pumpManifest(actions = listOf(calibrateDescriptor())).toDeviceFormSchema()
        val action = schema.actions.single()

        assertEquals("calibrate".asName(), action.name)
        val task = assertNotNull(action.task)
        assertEquals("task.state".parseAsName(), task.stateProperty)
        assertEquals("task.progress".parseAsName(), task.progressProperty)
        assertEquals("cancel".asName(), task.cancelAction)
        assertTrue(DeviceFormRenderHint.Task in action.renderHints)
        assertEquals(DeviceFormControlKind.TaskLink, action.controlKind)
        assertTrue(DeviceFormCapability.Tasks in schema.rendererProfile.capabilities)
        val taskCommandKinds = schema.commands
            .map { it.kind }
            .filter {
                it == DeviceFormCommandKind.ExecuteAction ||
                        it == DeviceFormCommandKind.OpenTaskState ||
                        it == DeviceFormCommandKind.CancelTask
            }
        assertEquals(
            listOf(DeviceFormCommandKind.ExecuteAction, DeviceFormCommandKind.OpenTaskState, DeviceFormCommandKind.CancelTask),
            taskCommandKinds,
        )
        assertTrue(schema.bindings.any { it.kind == DeviceFormBindingKind.TaskState })
        assertTrue(schema.bindings.any { it.kind == DeviceFormBindingKind.TaskProgress })
    }

    @Test
    fun formSchemaRoundTripsThroughJson() {
        val original = pumpManifest(actions = listOf(calibrateDescriptor())).toDeviceFormSchema()
        val json = Json.encodeToString(DeviceFormSchema.serializer(), original)
        val decoded = Json.decodeFromString(DeviceFormSchema.serializer(), json)

        assertEquals(original, decoded)
    }

    @Test
    fun liveDeviceProjectionIncludesDynamicOverlay() {
        val device = OverlayDevice(
            propertyDescriptors = pumpManifest().properties,
            actionDescriptors = pumpManifest().actions,
            discoveredPropertyDescriptors = mapOf("debug.raw".parseAsName() to debugDescriptor()),
        )

        val schema = device.snapshotDeviceFormSchema(version = "live")

        assertEquals("demo.device".asName(), schema.manifestId)
        assertEquals("live", schema.manifestVersion)
        assertEquals(DynamicDiscoveryPolicy.Learn, schema.dynamicDiscoveryPolicy)
        assertEquals(listOf("debug.raw"), schema.discoveredProperties.map { it.name.toString() })
    }

    @Test
    fun observedMetaProjectionKeepsQualityAndTime() {
        val observed = ObservedValue(
            value = Meta.EMPTY,
            time = Instant.parse("2026-07-03T10:15:30Z"),
            quality = DataQuality(QualitySeverity.UNCERTAIN, detail = "stale"),
        )

        val dto = observed.toDeviceFormObservedMeta()

        assertEquals("2026-07-03T10:15:30Z", dto.time)
        assertEquals(50, dto.quality.severity)
        assertEquals("UNCERTAIN", dto.quality.label)
        assertEquals("stale", dto.quality.detail)
    }
}

private fun pumpManifest(
    actions: List<ActionDescriptor> = listOf(resetDescriptor()),
) = manifestOf(
    id = "demo.pump".parseAsName(),
    properties = listOf(rpmDescriptor(), temperatureDescriptor()).associateBy { it.name },
    actions = actions.associateBy { it.name },
    version = "1.2.3",
    deviceContractFqName = "demo.Pump",
)

private fun rpmDescriptor(): PropertyDescriptor = PropertyDescriptor(
    name = "rpm".asName(),
    kind = PropertyKind.MEASURED,
    valueTypeId = TypeIds.DOUBLE,
    attributes = operationAttributesOf(
        OperationAttributeKeys.Metadata of MetadataAttribute(description = "Shaft speed", unit = "rpm"),
        OperationAttributeKeys.Access of AccessAttribute(readable = true, mutable = true),
        OperationAttributeKeys.EngineeringRange of EngineeringRangeAttribute(displayMin = 10.0, displayMax = 4000.0),
        OperationAttributeKeys.AcquisitionPolicy of AcquisitionPolicyAttribute(defaultMaxRateHz = 20.0),
        OperationAttributeKeys.DeliveryClass of DeliveryClassAttribute(MessageDeliveryClass.CriticalTelemetry),
    ),
)

private fun temperatureDescriptor(): PropertyDescriptor = PropertyDescriptor(
    name = "temperature".asName(),
    kind = PropertyKind.PHYSICAL,
    valueTypeId = TypeIds.DOUBLE,
)

private fun resetDescriptor(): ActionDescriptor = ActionDescriptor(
    name = "reset".asName(),
    inputMetaDescriptor = MetaDescriptor(),
    outputMetaDescriptor = MetaDescriptor(),
    attributes = operationAttributesOf(
        OperationAttributeKeys.Metadata of MetadataAttribute(description = "Reset command"),
    ),
)

private fun calibrateDescriptor(): ActionDescriptor = ActionDescriptor(
    name = "calibrate".asName(),
    inputMetaDescriptor = MetaDescriptor(),
    outputMetaDescriptor = MetaDescriptor(),
    attributes = operationAttributesOf(
        OperationAttributeKeys.Task of TaskAttribute(
            stateProperty = "task.state".parseAsName(),
            progressProperty = "task.progress".parseAsName(),
            cancelAction = "cancel".asName(),
        ),
    ),
)

private fun debugDescriptor(): PropertyDescriptor = PropertyDescriptor(
    name = "debug.raw".parseAsName(),
    kind = PropertyKind.LOGICAL,
    valueTypeId = TypeIds.META,
)

@OptIn(UnstableKrigForSubclassing::class)
private class OverlayDevice(
    override val propertyDescriptors: Map<Name, PropertyDescriptor>,
    override val actionDescriptors: Map<Name, ActionDescriptor>,
    override val discoveredPropertyDescriptors: Map<Name, PropertyDescriptor>,
) : AbstractDevice("demo.device".asName(), DeviceRuntime.from(Global)), DynamicDescriptorOverlay {
    override val dynamicDiscoveryPolicy: DynamicDiscoveryPolicy = DynamicDiscoveryPolicy.Learn
}
