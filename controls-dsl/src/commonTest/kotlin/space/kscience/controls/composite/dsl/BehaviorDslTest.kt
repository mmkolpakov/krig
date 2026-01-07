package space.kscience.controls.composite.dsl

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.composite.dsl.guards.guards
import space.kscience.controls.composite.dsl.guards.post
import space.kscience.controls.composite.dsl.properties.predicate
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.fsm.OperationalFsmFeature
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.fsm.lifecycleFeature
import space.kscience.controls.fsm.operationalFsm
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.misc.DFExperimental
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Serializable
private object MyGuardEvent : Event

/**
 * A test suite for DSL constructs related to device behavior, such as FSMs and guards.
 */
//not all passed
class BehaviorDslTest {

    /**
     * Verifies that the `guards` block correctly creates an [OperationalGuardsFeature]
     * and populates it with the defined [GuardSpec]s.
     */
    @OptIn(DFExperimental::class)
    @Test
//    FAILED
    fun testGuardsRegistration() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val isReady by predicate { true }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                guards {
                    whenTrue(isReady).forAtLeast(2.seconds).post<MyGuardEvent>()
                }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)
        val feature = blueprint[OperationalGuardsFeature]
        assertIs<OperationalGuardsFeature>(feature)
        assertEquals(1, feature.guards.size)
        val guard = feature.guards.first()
        assertIs<TimedPredicateGuardSpec>(guard)
        assertEquals("isReady", guard.predicateName.toString())
        val expectedEventName = serializer<MyGuardEvent>().descriptor.serialName
        assertEquals(expectedEventName, guard.postEventSerialName)
    }

    /**
     * Verifies the integration between the `guards` and `operationalFsm` blocks,
     * ensuring that event names from guards are automatically added to the [OperationalFsmFeature].
     */
    @OptIn(DFExperimental::class)
    @Test
//    FAILED
    fun testGuardsAndFsmIntegration() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val isReady by predicate { true }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                operationalFsm(setOf("Idle", "Running")) { _, _ ->
                    // FSM definition here
                }
                guards {
                    whenTrue(isReady).forAtLeast(1.seconds).post<MyGuardEvent>()
                }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)
        val feature = blueprint[OperationalFsmFeature]
        assertIs<OperationalFsmFeature>(feature)

        val expectedEventName = serializer<MyGuardEvent>().descriptor.serialName
        assertTrue(
            feature.events.contains(expectedEventName),
            "Event from guard should be automatically added to the FSM feature."
        )
    }

    /**
     * Verifies that the `lifecycle` and `operationalFsm` DSL blocks correctly
     * capture their lambda bodies and store them in the final [DeviceBlueprint].
     */
    @Test
//    FAILED
    fun testLifecycleAndFsmLambdaCapture() {
        val blueprint = deviceBlueprintUnchecked<Device>("test", Global) {
            driver { _, _ -> error("Not for runtime") }
            lifecycle { _, _ -> }
            operationalFsm(setOf("Idle")) { _, _ -> }
        }

        assertNotNull(blueprint.lifecycleFeature)
        assertNotNull(blueprint.operationalFsm)
        assertTrue(blueprint.features.containsKey(OperationalFsmFeature.ID))
    }
}