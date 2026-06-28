@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestOfTest {
    private object Contract : DeviceContractBuilder() {
        val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        val command by action(MetaConverter.string, MetaConverter.string)
    }

    @Test
    fun manifestOfCopiesContractDescriptors() {
        val manifest = manifestOf(
            id = "space.kscience.krig.test.manifest",
            contract = Contract,
            version = "test",
        )

        assertEquals("space.kscience.krig.test.manifest", manifest.id.toString())
        assertEquals("test", manifest.version)
        assertTrue(Contract.value.name in manifest.properties)
        assertTrue(Contract.command.name in manifest.actions)
        assertEquals(Contract.value.descriptor, manifest.properties.getValue(Contract.value.name))
        assertEquals(Contract.command.descriptor, manifest.actions.getValue(Contract.command.name))
    }

    @Test
    fun manifestOfSnapshotsDescriptorMaps() {
        val properties = mutableMapOf(Contract.value.name to Contract.value.descriptor)
        val actions = mutableMapOf(Contract.command.name to Contract.command.descriptor)

        val manifest = manifestOf(
            id = "space.kscience.krig.test.snapshot".asName(),
            properties = properties,
            actions = actions,
        )
        properties.clear()
        actions.clear()

        assertEquals(setOf(Contract.value.name), manifest.properties.keys)
        assertEquals(setOf(Contract.command.name), manifest.actions.keys)
    }
}
