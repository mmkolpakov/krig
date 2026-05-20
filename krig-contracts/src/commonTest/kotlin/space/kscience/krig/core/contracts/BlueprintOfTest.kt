@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.core.contracts

import space.kscience.krig.core.meta.DeviceSpecBuilder
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.dataforge.meta.MetaConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintOfTest {
    private object Spec : DeviceSpecBuilder<Device>() {
        val value by mutableDoubleProperty(
            read = { 1.0 },
            write = { },
        )
        val command by action(MetaConverter.string, MetaConverter.string) { input ->
            input
        }
    }

    @Test
    fun blueprintOfCopiesSpecDescriptors() {
        val blueprint = blueprintOf(
            id = "space.kscience.krig.test.blueprint",
            spec = Spec,
            version = "test",
        )

        assertEquals("space.kscience.krig.test.blueprint", blueprint.id.toString())
        assertEquals("test", blueprint.version)
        assertTrue(Spec.value.name in blueprint.properties)
        assertTrue(Spec.command.name in blueprint.actions)
        assertEquals(Spec.value.descriptor, blueprint.properties.getValue(Spec.value.name))
        assertEquals(Spec.command.descriptor, blueprint.actions.getValue(Spec.command.name))
    }
}
