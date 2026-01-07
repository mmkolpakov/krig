package space.kscience.controls.composite.dsl

import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

//all passed
class SpecificationFragmentTest {

    private val fragmentA = specificationFragment {
        doubleProperty("propA".asName()) { 1.0 }
        meta { "source" put "A" }
    }

    private val fragmentB = specificationFragment {
        doubleProperty("propB".asName()) { 2.0 }
        meta { "source" put "B" }
    }

    @Test
    fun `should combine fragments and apply them in order`() {
        val combinedFragment = fragmentA + fragmentB

        val testSpec = object : DeviceSpecification<Device>() {
            override val id = "test.spec.fragment.composition"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                include(combinedFragment)
            }
        }

        val blueprint = compositeDeviceUnchecked(testSpec, Global)

        assertNotNull(blueprint.properties["propA".asName()])
        assertNotNull(blueprint.properties["propB".asName()])
        assertEquals("B", blueprint.meta["source"].string)
    }
}