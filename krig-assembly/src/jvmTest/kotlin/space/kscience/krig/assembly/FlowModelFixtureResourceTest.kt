package space.kscience.krig.assembly

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowModelFixtureResourceTest {

    @Test
    fun chemicalFactoryResourceIsLoadable() {
        val resource = javaClass.classLoader.getResource("flow-model/ChemicalFactory.json")

        assertNotNull(resource)
        val model = FlowModelConfiguration.fromJsonString(resource.readText())
        val diagnostics = model.validateCompatibilityTarget()

        assertTrue(diagnostics.none { it.severity == FlowModelDiagnosticSeverity.Error }, diagnostics.toString())
    }
}
