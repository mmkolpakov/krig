package space.kscience.krig.core.operations

import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyTypeConsistencyValidationHookTest {

    private fun descriptor(typeId: space.kscience.krig.api.descriptors.TypeId, valueTypes: List<ValueType>?) =
        PropertyDescriptor(
            name = "p".asName(),
            kind = PropertyKind.PHYSICAL,
            valueTypeId = typeId,
            metaDescriptor = MetaDescriptor(valueTypes = valueTypes),
        )

    @Test
    fun scalarTypeContradictingMetaDescriptorIsFlagged() {
        val finding = validatePropertyType(descriptor(TypeIds.DOUBLE, listOf(ValueType.STRING)))
        assertEquals(ManifestValidationMessage.Severity.WARNING, finding?.severity)
        assertEquals("type.consistency", finding?.category)
    }

    @Test
    fun scalarTypeMatchingMetaDescriptorPasses() {
        assertNull(validatePropertyType(descriptor(TypeIds.DOUBLE, listOf(ValueType.NUMBER))))
    }

    @Test
    fun unconstrainedMetaDescriptorPasses() {
        assertNull(validatePropertyType(descriptor(TypeIds.DOUBLE, valueTypes = null)))
    }

    @Test
    fun nodeShapedTypeIdCarriesNoScalarExpectation() {
        assertNull(validatePropertyType(descriptor(TypeIds.META, listOf(ValueType.STRING))))
    }
}
