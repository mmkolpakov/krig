package space.kscience.krig.assembly

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessFlowFixtureResourceTest {

    @Test
    fun chemicalFactoryResourceIsLoadable() {
        val resource = javaClass.classLoader.getResource("process-flow/ChemicalFactory.json")

        assertNotNull(resource)
        val model = ExternalFlowModelDocument.fromJsonString(resource.readText())
        val diagnostics = model.validateProcessFlowDialect()

        assertTrue(diagnostics.none { it.severity == ProcessFlowDiagnosticSeverity.Error }, diagnostics.toString())
    }
}
