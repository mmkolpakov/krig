package space.kscience.krig.simulation

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SimulationPluginAliasTest {

    @Test
    fun simulationPluginAliasesClockManager() {
        assertEquals(ClockManager.tag, SimulationPlugin.tag)

        val context = Context("simulation-alias") { plugin(SimulationPlugin) }
        val viaAlias: SimulationPlugin = context.request(SimulationPlugin)
        val viaClass: ClockManager = context.request(ClockManager)
        assertSame(viaClass, viaAlias)
    }
}
