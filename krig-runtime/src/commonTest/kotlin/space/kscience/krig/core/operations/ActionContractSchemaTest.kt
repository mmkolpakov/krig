package space.kscience.krig.core.operations

import kotlinx.serialization.Serializable
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.meta.serializableActionContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(DFExperimental::class)
class ActionContractSchemaTest {

    @Serializable
    private data class MoveArgs(val x: Double, val y: Double)

    @Serializable
    private data class MoveResult(val ok: Boolean)

    @Test
    fun serializableContractPopulatesArgumentSchema() {
        val contract = serializableActionContract(
            "move".asName(),
            inputConverter = MetaConverter.serializable<MoveArgs>(),
            outputConverter = MetaConverter.serializable<MoveResult>(),
        )

        assertEquals(setOf("x", "y"), contract.descriptor.inputMetaDescriptor.nodes.keys)

        val schema = contract.descriptor.toJsonSchema()
        assertTrue("input" in schema)
        assertTrue("output" in schema)
    }
}
