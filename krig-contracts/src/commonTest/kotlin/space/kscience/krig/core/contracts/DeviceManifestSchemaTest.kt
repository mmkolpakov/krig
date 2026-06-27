package space.kscience.krig.core.contracts

import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.deviceContractRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceManifestSchemaTest {

    private fun numberProperty(name: String) = PropertyDescriptor(
        name = name.asName(),
        kind = PropertyKind.PHYSICAL,
        valueTypeId = TypeIds.DOUBLE,
        metaDescriptor = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)),
    )

    @Test
    fun propertyJsonSchemaReflectsValueType() {
        val schema = numberProperty("rpm").toJsonSchema()
        assertEquals("number", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun propertyValidatesMetaAgainstDescriptor() {
        val descriptor = numberProperty("rpm")
        assertTrue(descriptor.validateMeta(Meta(42.0.asValue())))
    }

    @Test
    fun schemaHashIsStableAcrossMapInsertionOrder() {
        val rpm = numberProperty("rpm")
        val temp = numberProperty("temp")
        val first = manifestOf(
            "lab.device".asName(),
            properties = linkedMapOf(rpm.name to rpm, temp.name to temp),
        )
        val second = manifestOf(
            "lab.device".asName(),
            properties = linkedMapOf(temp.name to temp, rpm.name to rpm),
        )

        assertEquals(first.toJsonSchema(), second.toJsonSchema())
        assertEquals(first.schemaHash(), second.schemaHash())
    }

    @Test
    fun deviceContractRegistryCapturesTypedContractsAndManifest() {
        val contract = object : DeviceContractBuilder() {
            val target by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        }
        val registry = deviceContractRegistry(
            id = "lab.registry".asName(),
            contract = contract,
            version = "1.0.0",
            deviceContractFqName = "sample.RegistryContract",
        )

        assertEquals("1.0.0", registry.version)
        assertEquals(contract.target, registry.propertiesByName.getValue(contract.target.name))
        assertEquals(contract.target.descriptor, registry.manifest.properties.getValue(contract.target.name))
        assertEquals(registry.manifest.schemaHash(), registry.schemaHash)
    }
}
