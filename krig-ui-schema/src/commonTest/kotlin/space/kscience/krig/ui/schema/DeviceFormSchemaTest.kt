package space.kscience.krig.ui.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.of
import space.kscience.krig.api.descriptors.operationAttributesOf
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.MetadataAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.core.contracts.manifestOf

class DeviceFormSchemaTest {

    @Test
    fun manifestProjectionCarriesContractFields() {
        val schema = pumpManifest().toDeviceFormSchema()

        assertEquals("demo.pump".parseAsName(), schema.manifestId)
        assertEquals("1.2.3", schema.manifestVersion)
        assertEquals("demo.Pump", schema.deviceContractFqName)
        assertTrue(schema.schemaHash.startsWith("fnv1a64:"))
        assertEquals(listOf("rpm", "temperature"), schema.properties.map { it.name.toString() })

        val rpm = schema.properties.first { it.name == "rpm".asName() }
        assertEquals(PropertyKind.MEASURED, rpm.kind)
        assertEquals(TypeIds.DOUBLE, rpm.valueTypeId)
        assertTrue(rpm.readable)
        assertTrue(rpm.mutable)
        assertEquals("Shaft speed", rpm.description)
        assertEquals("rpm", rpm.unit)
        assertEquals(DeviceFormPropertyOrigin.Manifest, rpm.origin)

        val action = schema.actions.single()
        assertEquals("reset".asName(), action.name)
        assertEquals("Reset command", action.description)
    }

    @Test
    fun discoveredPropertiesStayOutsideManifestProperties() {
        val discovered = PropertyDescriptor(
            name = "debug.raw".parseAsName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.META,
        )

        val schema = pumpManifest().toDeviceFormSchema(discoveredProperties = listOf(discovered))

        assertEquals(listOf("rpm", "temperature"), schema.properties.map { it.name.toString() })
        assertEquals(listOf("debug.raw"), schema.discoveredProperties.map { it.name.toString() })
        assertEquals(DeviceFormPropertyOrigin.Discovered, schema.discoveredProperties.single().origin)
    }

    @Test
    fun formSchemaRoundTripsThroughJson() {
        val original = pumpManifest().toDeviceFormSchema()
        val json = Json.encodeToString(DeviceFormSchema.serializer(), original)
        val decoded = Json.decodeFromString(DeviceFormSchema.serializer(), json)

        assertEquals(original, decoded)
    }
}

private fun pumpManifest() = manifestOf(
    id = "demo.pump".parseAsName(),
    properties = listOf(rpmDescriptor(), temperatureDescriptor()).associateBy { it.name },
    actions = listOf(resetDescriptor()).associateBy { it.name },
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
