package space.kscience.krig.schema.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import me.kpavlov.kt.schema.json.JsonSchemaConstants
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.manifestOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KrigJsonSchemaTest {

    @Test
    fun metaDescriptorProjectionKeepsValueType() {
        val schema = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)).toKrigJsonSchema()

        assertEquals(listOf(JsonSchemaConstants.Types.NUMBER), schema.type)
        assertEquals("number", schema.toJsonObject()["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun manifestProjectionIsTypedAndStable() {
        val rpm = numberProperty("rpm")
        val temp = numberProperty("temperature")
        val schema = manifestOf(
            id = "demo.pump".asName(),
            properties = linkedMapOf(temp.name to temp, rpm.name to rpm),
            version = "1.0.0",
            deviceContractFqName = "demo.Pump",
        ).toKrigJsonSchema()

        assertEquals(JsonSchemaConstants.JSON_SCHEMA_ID_DRAFT202012, schema.schema)
        assertEquals(listOf("rpm", "temperature"), schema.properties.keys.toList())
        assertNotNull(schema.properties["rpm"])
    }

    @Test
    fun serializableDtoProjectionUsesKtSchemaGenerator() {
        val schema = serializableKrigJsonSchema<SampleDto>()

        assertTrue("name" in schema.properties)
        assertTrue("enabled" in schema.properties)
    }
}

private fun numberProperty(name: String) = PropertyDescriptor(
    name = name.asName(),
    kind = PropertyKind.PHYSICAL,
    valueTypeId = TypeIds.DOUBLE,
    metaDescriptor = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)),
)

@Serializable
private data class SampleDto(
    val name: String,
    val enabled: Boolean = true,
)
