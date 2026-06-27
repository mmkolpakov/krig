package space.kscience.krig.api.features

import space.kscience.krig.generated.space.kscience.model.generatedKrigSerializersModule
import kotlin.test.Test
import kotlin.test.assertNotNull

class GeneratedKspCommonMetadataTest {

    @Test
    fun generatedSerializersModuleIsVisibleFromCommonTests() {
        assertNotNull(generatedKrigSerializersModule)
    }
}
