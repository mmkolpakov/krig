package space.kscience.krig.core.operations

import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.toJsonSchema
import space.kscience.krig.core.contracts.validateMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestJsonSchemaTest {

    private fun numberProperty() = PropertyDescriptor(
        name = "rpm".asName(),
        kind = PropertyKind.PHYSICAL,
        valueTypeId = TypeIds.DOUBLE,
        metaDescriptor = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)),
    )

    @Test
    fun propertyJsonSchemaReflectsValueType() {
        val schema = numberProperty().toJsonSchema()
        assertEquals("number", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun propertyValidatesMetaAgainstDescriptor() {
        val descriptor = numberProperty()
        assertTrue(descriptor.validateMeta(Meta(42.0.asValue())))
    }

    @Test
    fun selfConsistentDefaultsProduceNoFindings() {
        val manifest = manifestOf("test.device".asName(), properties = mapOf("rpm".asName() to numberProperty()))
        assertTrue(MetaDescriptorDefaultsValidationHook.validate(manifest).isEmpty())
    }
}
