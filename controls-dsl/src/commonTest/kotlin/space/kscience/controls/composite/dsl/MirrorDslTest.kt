package space.kscience.controls.composite.dsl

import kotlinx.serialization.serializer
import space.kscience.controls.composite.dsl.children.mirror
import space.kscience.controls.composite.dsl.children.mirrors
import space.kscience.controls.composite.dsl.properties.doubleProperty
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.connectivity.RemoteMirrorFeature
import space.kscience.controls.core.descriptors.PropertyKind
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private class RemoteDeviceSpecForMirror : DeviceSpecification<Device>() {
    override val id = "test.remote"
    val remoteValue by doubleProperty { 42.0 }
    override fun CompositeSpecBuilder<Device>.configure() {
        driver { _, _ -> error("Not for runtime") }
    }
}

/**
 * A test suite for the `mirrors` DSL.
 */
//all passed
class MirrorDslTest {

    private val remoteSpec = RemoteDeviceSpecForMirror()
    private val remoteBlueprint = compositeDeviceUnchecked(remoteSpec, Global)

    /**
     * Verifies that the `mirrors` block correctly creates a [RemoteMirrorFeature]
     * and populates it with the defined [MirrorEntry]s.
     */
    @Test
    fun testMirrorRegistration() {
        val localSpec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                val remotePeer by peer({ _, _ -> error("Not for runtime") }, Address("remoteHub", "peer"))
                remoteChild("remote".asName(), remoteBlueprint, "remoteDevice".asName(), remotePeer)
                mirrors {
                    mirror("remote".asName(), remoteSpec.remoteValue, "localValue".asName(), MetaConverter.double)
                }
            }
        }
        val blueprint = compositeDeviceUnchecked(localSpec, Global)

        val feature = blueprint[RemoteMirrorFeature]
        assertIs<RemoteMirrorFeature>(feature)
        assertEquals(1, feature.entries.size)

        val entry = feature.entries.first()
        assertEquals("remote".asName(), entry.remoteChildName)
        assertEquals("remoteValue".asName(), entry.remotePropertyName)
        assertEquals("localValue".asName(), entry.localPropertyName)
        assertEquals(serializer<Double>().descriptor.serialName, entry.valueTypeName)
    }

    /**
     * Verifies that the `mirror` DSL function not only registers a `MirrorEntry`
     * but also creates a corresponding local, read-only property with `PropertyKind.DERIVED`.
     */
    @Test
    fun testMirrorCreatesProxyProperty() {
        val localSpec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                val remotePeer by peer({ _, _ -> error("Not for runtime") }, Address("remoteHub", "peer"))
                remoteChild("remote".asName(), remoteBlueprint, "remoteDevice".asName(), remotePeer)
                mirrors {
                    mirror("remote".asName(), remoteSpec.remoteValue, "localValue".asName(), MetaConverter.double)
                }
            }
        }
        val blueprint = compositeDeviceUnchecked(localSpec, Global)

        val localPropertySpec = blueprint.properties["localValue".asName()]
        assertNotNull(localPropertySpec)
        assertEquals(PropertyKind.DERIVED, localPropertySpec.descriptor.kind)
        assertEquals(false, localPropertySpec.descriptor.mutable)
        assertEquals(
            "A mirror of property 'remoteValue' from remote child 'remote'.",
            localPropertySpec.descriptor.description
        )
    }
}