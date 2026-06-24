package space.kscience.krig.api.descriptors

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.allowedValues
import space.kscience.dataforge.meta.descriptors.required
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerialDescriptorMetaTest {

    @Serializable
    private enum class Mode { FAST, SLOW }

    @Serializable
    private data class MoveArgs(
        val x: Double,
        val y: Double,
        val mode: Mode,
        val label: String? = null,
    )

    @Test
    fun derivesFieldsTypesAndRequiredness() {
        val descriptor = metaDescriptorOf<MoveArgs>()

        assertEquals(setOf("x", "y", "mode", "label"), descriptor.nodes.keys)
        assertEquals(listOf(ValueType.NUMBER), descriptor.nodes.getValue("x").valueTypes)
        assertTrue(descriptor.nodes.getValue("x").required)
        val mode = descriptor.nodes.getValue("mode")
        assertEquals(listOf(ValueType.STRING), mode.valueTypes)
        assertEquals(listOf(Mode.FAST, Mode.SLOW).size, assertNotNull(mode.allowedValues).size)
        assertFalse(descriptor.nodes.getValue("label").required)
    }
}
