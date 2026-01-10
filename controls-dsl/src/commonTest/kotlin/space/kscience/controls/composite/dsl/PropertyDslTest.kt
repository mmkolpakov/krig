package space.kscience.controls.composite.dsl

import kotlinx.serialization.Serializable
import space.kscience.controls.composite.dsl.properties.*
import space.kscience.controls.composite.persistence.StatefulFeature
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.runtime.CompositeDeviceContext
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.api.descriptors.mutable
import space.kscience.controls.api.descriptors.persistent
import space.kscience.controls.core.state.StatefulDevice
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private interface TestStatefulDeviceForDsl : Device, StatefulDevice, CompositeDeviceContext

@Serializable
private data class MySerializableData(val text: String, val number: Int)

/**
 * A test suite for property-related DSL delegates in [DeviceSpecification].
 */
//not all passed
class PropertyDslTest {

    /**
     * Verifies that `property` and `mutableProperty` delegates correctly create and register
     * a `DevicePropertySpec` with `PropertyKind.PHYSICAL`.
     */
    @Test
    fun testPhysicalPropertyDelegates() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val readOnly by property(MetaConverter.string, read = { "value" })
            val mutable by mutableProperty(MetaConverter.int, read = { 1 }, write = {})
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val readOnlySpec = blueprint.properties["readOnly".asName()]
        assertNotNull(readOnlySpec)
        assertEquals(PropertyKind.PHYSICAL, readOnlySpec.descriptor.kind)
        assertEquals(false, readOnlySpec.descriptor.mutable)
        assertEquals("kotlin.String", readOnlySpec.descriptor.valueTypeName)

        val mutableSpec = blueprint.properties["mutable".asName()]
        assertNotNull(mutableSpec)
        assertEquals(PropertyKind.PHYSICAL, mutableSpec.descriptor.kind)
        assertEquals(true, mutableSpec.descriptor.mutable)
    }

    /**
     * Verifies that the `stateProperty` delegate correctly creates a `DevicePropertySpec`
     * with `PropertyKind.LOGICAL`, marks it as persistent, and adds the `StatefulFeature`.
     */
    @Test
//    FAILED StatefulFeature should be added automatically.
    fun testStatefulPropertyDelegate() {
        val spec = object : DeviceSpecification<TestStatefulDeviceForDsl>() {
            override val id = "test.device"
            val myState by stateProperty(MetaConverter.boolean, false)
            override fun CompositeSpecBuilder<TestStatefulDeviceForDsl>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val stateSpec = blueprint.properties["myState".asName()]
        assertNotNull(stateSpec)
        assertEquals(PropertyKind.LOGICAL, stateSpec.descriptor.kind)
        assertTrue(stateSpec.descriptor.persistent, "Stateful property should be persistent by default.")
        assertTrue(
            blueprint.features.containsKey(StatefulFeature.ID),
            "StatefulFeature should be added automatically."
        )
    }

    /**
     * Verifies that the `derived` delegate creates a `DevicePropertySpec` with `PropertyKind.DERIVED`
     * and correctly registers its hydration logic.
     */
    @OptIn(DFExperimental::class, InternalControlsApi::class)
    @Test
    fun testDerivedPropertyDelegate() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val source by intProperty { 123 }
            val derived by derived(source, MetaConverter.string, initialValue = "initial") {
                it?.toString() ?: "default"
            }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val derivedSpec = blueprint.properties["derived".asName()]
        assertNotNull(derivedSpec)
        assertEquals(PropertyKind.DERIVED, derivedSpec.descriptor.kind)
        assertTrue(spec.registeredHydrators.containsKey("derived".asName()), "Hydrator for derived property should be registered.")
    }

    /**
     * Verifies that the `predicate` delegate creates a `DevicePropertySpec` with `PropertyKind.PREDICATE`.
     */
    @OptIn(DFExperimental::class)
    @Test
    fun testPredicateDelegate() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val source by booleanProperty { true }
            val myPredicate by predicate(source) { it ?: false }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val predicateSpec = blueprint.properties["myPredicate".asName()]
        assertNotNull(predicateSpec)
        assertEquals(PropertyKind.PREDICATE, predicateSpec.descriptor.kind)
    }

    /**
     * Verifies that the new `serializableProperty` and `mutableSerializableProperty` delegates
     * correctly create a `DevicePropertySpec` with an automatically inferred `MetaConverter`.
     */
    @Test
    fun testSerializablePropertyDelegates() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.serializable"

            val readOnly by serializableProperty<_, MySerializableData>(read = { MySerializableData("test", 1) })

            val mutable by mutableSerializableProperty<_, MySerializableData>(
                read = { MySerializableData("mutable", 2) },
                write = {}
            )

            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val readOnlySpec = blueprint.properties["readOnly".asName()]
        assertNotNull(readOnlySpec)
        assertEquals(false, readOnlySpec.descriptor.mutable)
        // Check if the type name was correctly inferred and stored in the descriptor
        assertEquals(
            "space.kscience.controls.composite.dsl.MySerializableData",
            readOnlySpec.descriptor.valueTypeName
        )

        val mutableSpec = blueprint.properties["mutable".asName()]
        assertNotNull(mutableSpec)
        assertEquals(true, mutableSpec.descriptor.mutable)
        assertEquals(
            "space.kscience.controls.composite.dsl.MySerializableData",
            mutableSpec.descriptor.valueTypeName
        )
    }
}