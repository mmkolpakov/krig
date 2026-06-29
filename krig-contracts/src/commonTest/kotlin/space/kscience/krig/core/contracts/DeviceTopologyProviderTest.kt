package space.kscience.krig.core.contracts

import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.provider.provide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DeviceTopologyProviderTest {
    @Test
    fun providerResolvesNestedDeviceEntries() {
        val motor = SimulatedDoubleSource(context = freshTestContext("topology-provider-motor"))
        val root = deviceTree(
            children = mapOf(
                "area".asName() to deviceTree(
                    children = mapOf("motor".asName() to motor.asNode()),
                ),
            ),
        )
        val provider = root.asTopologyProvider()

        val entry = provider.provide<DeviceTopologyEntry>("area/motor")

        assertNotNull(entry)
        assertEquals("area.motor".parseAsName(), entry.path)
        assertSame(motor, entry.device)
        assertSame(motor, provider.device("area/motor"))
    }

    @Test
    fun manifestProjectionSnapshotsCurrentDeviceDescriptors() {
        val motor = SimulatedDoubleSource(context = freshTestContext("topology-provider-manifest"))
        val provider = deviceTree(
            children = mapOf("motor".asName() to motor.asNode()),
        ).asTopologyProvider()

        val manifest = provider.manifest("motor")

        assertNotNull(manifest)
        assertEquals("motor".asName(), manifest.id)
        assertEquals(motor.propertyDescriptors, manifest.properties)
        assertEquals(motor.actionDescriptors, manifest.actions)
    }
}
