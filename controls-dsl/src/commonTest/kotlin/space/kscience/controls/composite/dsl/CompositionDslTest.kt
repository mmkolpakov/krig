package space.kscience.controls.composite.dsl

import space.kscience.controls.composite.dsl.properties.doubleProperty
import space.kscience.controls.composite.dsl.properties.mutableDoubleProperty
import space.kscience.controls.connectivity.ChildBindingsFeature
import space.kscience.controls.connectivity.ConstPropertyBinding
import space.kscience.controls.connectivity.LinearTransformDescriptor
import space.kscience.controls.connectivity.ParentPropertyBinding
import space.kscience.controls.connectivity.TransformedPropertyBinding
import space.kscience.controls.connectivity.composition
import space.kscience.controls.connectivity.connectivity
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.composition.LocalChildComponentConfig
import space.kscience.controls.api.composition.RemoteChildComponentConfig
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

// --- Mocks and Test Fixtures ---

private class ChildSpecForComposition : DeviceSpecification<Device>() {
    override val id = "test.child"
    val childTarget by mutableDoubleProperty(read = { 0.0 }, write = { _ -> })
    val readOnlyChildProp by doubleProperty(read = { 1.0 })
    override fun CompositeSpecBuilder<Device>.configure() {
        driver { _, _ -> error("Not for runtime") }
    }
}

/**
 * A test suite for DSL constructs related to device composition, including children, peers, and bindings.
 */
//not all passed
class CompositionDslTest {

    private val childSpec = ChildSpecForComposition()
    private val childBlueprint = compositeDeviceUnchecked(childSpec, Global)

    /**
     * Verifies that `child` and `children` blocks correctly register [LocalChildComponentConfig]
     * with the specified blueprint ID, metadata, and lifecycle overrides.
     */
    @Test
//    FAILED
    fun testLocalChildRegistration() {
        val parentSpec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                child("singleChild".asName(), childBlueprint) {
                    meta { "childKey" put "childValue" }
                }
                children(childBlueprint, listOf("multiChild1".asName(), "multiChild2".asName()))
            }
        }
        val blueprint = compositeDeviceUnchecked(parentSpec, Global)
        val composition = blueprint.composition ?: fail("CompositionFeature missing")
        val children = composition.children

        assertEquals(3, children.size)

        val singleChildConfig = children["singleChild".asName()]
        assertIs<LocalChildComponentConfig>(singleChildConfig)
        assertEquals(childBlueprint.id, singleChildConfig.blueprintId)
        assertEquals("childValue", singleChildConfig.meta["childKey"].string)

        val multiChildConfig = children["multiChild2".asName()]
        assertIs<LocalChildComponentConfig>(multiChildConfig)
    }

    /**
     * Verifies the correct creation of all three types of property bindings:
     * constant, parent-to-child, and transformed parent-to-child.
     */
    @Test
//    FAILED
    fun testPropertyBindings() {
        val parentSpec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val parentSource by doubleProperty { 123.0 }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                child("boundChild".asName(), childBlueprint) {
                    bindings {
                        // 1. Bind to a constant value
                        bind(childSpec.childTarget, 42.0)
                        // 2. Bind to a parent property
                        childSpec.childTarget bindsTo parentSource
                        // 3. Bind with a transformation
                        childSpec.childTarget bindsTo parentSource using LinearTransformDescriptor(scale = 2.0)
                    }
                }
            }
        }
        val blueprint = compositeDeviceUnchecked(parentSpec, Global)

        val children = blueprint.composition?.children ?: emptyMap()
        val childConfig = children["boundChild".asName()] as LocalChildComponentConfig
        val bindingsFeature = childConfig.features.find { it.key == ChildBindingsFeature } as? ChildBindingsFeature
        assertNotNull(bindingsFeature, "ChildBindingsFeature should be present")

        val bindings = bindingsFeature.bindings
        assertEquals(3, bindings.size)

        val constBinding = bindings.find { it is ConstPropertyBinding }
        assertIs<ConstPropertyBinding>(constBinding)
        assertEquals(42.0, constBinding.value.double)

        val parentBinding = bindings.find { it is ParentPropertyBinding }
        assertIs<ParentPropertyBinding>(parentBinding)
        assertEquals(childSpec.childTarget.name, parentBinding.targetName)
        assertEquals(parentSpec.parentSource.name, parentBinding.sourceName)

        val transformedBinding = bindings.find { it is TransformedPropertyBinding }
        assertIs<TransformedPropertyBinding>(transformedBinding)
        assertEquals(2.0, (transformedBinding.transformer as LinearTransformDescriptor).scale)
    }

    /**
     * Verifies that the `remoteChild` DSL correctly creates a [RemoteChildComponentConfig]
     * and populates it with the correct remote device name and peer connection name.
     */
    @Test
//    FAILED
    fun testRemoteChildRegistration() {
        val parentSpec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                val remotePeer by peer({ _, _ -> error("Not for runtime") }, Address("remoteHub", "peer"))
                remoteChild(
                    name = "remoteProxy".asName(),
                    blueprint = childBlueprint,
                    remoteDeviceName = "actualRemoteDevice".asName(),
                    via = remotePeer
                )
            }
        }
        val blueprint = compositeDeviceUnchecked(parentSpec, Global)
        val remoteChildConfig = blueprint.composition?.children?.get("remoteProxy".asName())
        assertIs<RemoteChildComponentConfig>(remoteChildConfig)
        assertEquals("actualRemoteDevice".asName(), remoteChildConfig.remoteDeviceName)
        assertEquals("remotePeer".asName(), remoteChildConfig.peerName)
    }

    /**
     * Verifies that the `peer` delegate correctly registers a [PeerBlueprint]
     * in the blueprint's `peerConnections` map.
     */
    @Test
//    FAILED
    fun testPeerRegistration() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                val myPeer by peer({ _, _ -> error("Not for runtime") }, Address("remoteHub", "peer"))
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)
        val peers = blueprint.connectivity?.peerConnections ?: emptyMap()
        assertNotNull(peers["myPeer".asName()])
        assertEquals("myPeer", peers["myPeer".asName()]?.id)
    }
}