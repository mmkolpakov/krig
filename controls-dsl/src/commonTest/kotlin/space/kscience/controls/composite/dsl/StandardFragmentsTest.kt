package space.kscience.controls.composite.dsl

import space.kscience.controls.api.descriptors.description
import space.kscience.controls.api.descriptors.readPermissions
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.fsm.IntrospectionFeature
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A test suite for the standard, reusable [SpecificationFragment]s.
 */
//all passed
class StandardFragmentsTest {

    /**
     * Verifies that the `WithFsmDiagrams` fragment correctly:
     * 1. Registers the `IntrospectionFeature`.
     * 2. Registers the `getLifecycleFsmDiagram` and `getOperationalFsmDiagram` actions.
     * 3. Configures the actions with the correct descriptions and permissions.
     */
    @Test
    fun testWithFsmDiagramsFragment() {
        // Define a specification that includes the fragment.
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device.with.diagrams"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                // Include the standard fragment.
                include(StandardFragments.WithFsmDiagrams)
            }
        }

        // Build the blueprint.
        val blueprint = compositeDeviceUnchecked(spec, Global)

        // 1. Verify the feature is present.
        val feature = blueprint.features[IntrospectionFeature.CAPABILITY]
        assertIs<IntrospectionFeature>(feature, "IntrospectionFeature should be registered.")
        assertTrue(feature.providesFsmDiagrams, "The feature should indicate that diagrams are provided.")

        // 2. Verify the lifecycle diagram action.
        val lifecycleActionSpec = blueprint.actions[StandardFragments.GET_LIFECYCLE_FSM_DIAGRAM_ACTION_NAME.asName()]
        assertNotNull(lifecycleActionSpec, "The lifecycle diagram action should be registered.")
        assertEquals(
            "Returns a PlantUML string representation of the device's lifecycle FSM.",
            lifecycleActionSpec.descriptor.description
        )
//        TODO readPermissions only
        assertTrue(
            lifecycleActionSpec.descriptor.readPermissions.any { it.id == "device.diagnostics.read" },
            "Lifecycle diagram action should require 'device.diagnostics.read' permission."
        )

        // 3. Verify the operational diagram action.
        val operationalActionSpec = blueprint.actions[StandardFragments.GET_OPERATIONAL_FSM_DIAGRAM_ACTION_NAME.asName()]
        assertNotNull(operationalActionSpec, "The operational diagram action should be registered.")
        assertEquals(
            "Returns a PlantUML string representation of the device's operational FSM, if it exists.",
            operationalActionSpec.descriptor.description
        )
        assertTrue(
            operationalActionSpec.descriptor.readPermissions.any { it.id == "device.diagnostics.read" },
            "Operational diagram action should require 'device.diagnostics.read' permission."
        )
    }

    /**
     * Verifies that the fragment works correctly when used directly inside the `deviceBlueprint` DSL.
     */
    @Test
    fun testWithFsmDiagramsInDslBlock() {
        val blueprint = deviceBlueprintUnchecked<Device>("test.dsl.with.diagrams", Global) {
            driver { _, _ -> error("Not for runtime") }
            include(StandardFragments.WithFsmDiagrams)
        }

        val feature = blueprint.features[IntrospectionFeature.CAPABILITY]
        assertIs<IntrospectionFeature>(feature)
        assertNotNull(blueprint.actions[StandardFragments.GET_LIFECYCLE_FSM_DIAGRAM_ACTION_NAME.asName()])
    }
}