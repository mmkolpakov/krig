package space.kscience.controls.composite.dsl

import kotlinx.serialization.Serializable
import space.kscience.controls.composite.dsl.actions.metaAction
import space.kscience.controls.composite.dsl.actions.plan
import space.kscience.controls.composite.dsl.actions.taskAction
import space.kscience.controls.composite.dsl.actions.unitAction
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.descriptors.attribute
import space.kscience.controls.api.descriptors.attributes.ImplementationAttribute
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.automation.PlanExecutorDevice
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.automation.PlanExecutorFeature
import space.kscience.controls.automation.TaskExecutorFeature
import space.kscience.controls.common.meta.UnitMetaConverter
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// --- Mocks and Test Fixtures ---

private interface TestPlanExecutorDeviceForDsl : Device, PlanExecutorDevice
private interface TestTaskExecutorDeviceForDsl : Device, TaskExecutorDevice

@Serializable
private data class MyTaskInput(val value: String)

@Serializable
private data class MyTaskOutput(val length: Int)

//all passed
/**
 * A test suite for action-related DSL delegates in [DeviceSpecification].
 */
class ActionDslTest {

    /**
     * Verifies that `unitAction` and `metaAction` delegates correctly create a [DeviceActionSpec]
     * with the appropriate input and output converters.
     */
    @Test
    fun testUnitAndMetaActions() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.device"
            val doSomething by unitAction {}
            val processMeta by metaAction { it }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        val unitActionSpec = blueprint.actions["doSomething".asName()]
        assertNotNull(unitActionSpec)
        assertEquals(UnitMetaConverter, unitActionSpec.inputConverter)
        assertEquals(UnitMetaConverter, unitActionSpec.outputConverter)

        val metaActionSpec = blueprint.actions["processMeta".asName()]
        assertNotNull(metaActionSpec)
        assertEquals(MetaConverter.meta, metaActionSpec.inputConverter)
        assertEquals(MetaConverter.meta, metaActionSpec.outputConverter)
    }

    /**
     * Verifies that the `plan` delegate correctly adds the [PlanExecutorFeature]
     * and serializes the [TransactionPlan] into the action's descriptor metadata.
     */
    @Test
    fun testPlanAction() {
        val spec = object : DeviceSpecification<TestPlanExecutorDeviceForDsl>() {
            override val id = "test.device"
            val myPlan by plan {
                start(Address("myHub", "myDevice"))
            }
            override fun CompositeSpecBuilder<TestPlanExecutorDeviceForDsl>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        // Check if the feature is automatically added
        assertTrue(
            blueprint.features.containsKey(PlanExecutorDevice.CAPABILITY),
            "PlanExecutorFeature should be added automatically for plan actions."
        )

        val planActionSpec = blueprint.actions["myPlan".asName()]
        assertNotNull(planActionSpec)

        // Check if the plan is serialized into the descriptor's meta
        val planMeta = planActionSpec.descriptor.attribute<ImplementationAttribute>()?.executionMeta["plan"]
        assertNotNull(planMeta)
        assertEquals("start", planMeta["type"].string)
    }

    /**
     * Verifies that the `taskAction` delegate correctly adds the [TaskExecutorFeature]
     * and populates the action descriptor with task-related information.
     */
    @Test
    fun testTaskAction() {
        val spec = object : DeviceSpecification<TestTaskExecutorDeviceForDsl>() {
            override val id = "test.device"
            val myTask by taskAction<MyTaskInput, MyTaskOutput, _>(
                taskBlueprintId = "com.example.myTask"
            )
            override fun CompositeSpecBuilder<TestTaskExecutorDeviceForDsl>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val blueprint = compositeDeviceUnchecked(spec, Global)

        // Check if the feature is automatically added
        assertTrue(
            blueprint.features.containsKey(TaskExecutorDevice.CAPABILITY),
            "TaskExecutorFeature should be added automatically for task actions."
        )

        val taskActionSpec = blueprint.actions["myTask".asName()]
        assertNotNull(taskActionSpec)

        // Check descriptor fields
        val descriptor = taskActionSpec.descriptor
        assertEquals("com.example.myTask", descriptor.attribute<ImplementationAttribute>()?.taskBlueprintId)
        assertEquals("space.kscience.controls.composite.dsl.MyTaskInput", descriptor.attribute<ImplementationAttribute>()?.taskInputTypeName)
        assertEquals("space.kscience.controls.composite.dsl.MyTaskOutput", descriptor.attribute<ImplementationAttribute>()?.taskOutputTypeName)
    }
}