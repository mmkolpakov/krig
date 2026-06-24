package space.kscience.krig.core.hook

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * User-defined hook: a 3rd-party plugin owns its own [Hook] singleton, fires it at its own
 * integration point, and this test demonstrates the registry contract works without any
 * core changes.
 */
private object MetricsPulseHook : Hook<(Name) -> Unit>

@OptIn(ExperimentalCoroutinesApi::class)
class CustomHookTest {

    @Test
    fun userDefinedHookRoundtripsThroughPipelineBuilder() = runTest {
        val pipeline = PipelineBuilder()
        val recorded = mutableListOf<Name>()
        pipeline.register(MetricsPulseHook) { name -> recorded += name }

        // Third-party firing site — fully under user control.
        pipeline.handlersOf(MetricsPulseHook).forEach { it("sensor.x".asName()) }
        pipeline.handlersOf(MetricsPulseHook).forEach { it("sensor.y".asName()) }

        assertEquals(listOf("sensor.x".asName(), "sensor.y".asName()), recorded)
    }

    @Test
    fun multipleHandlersPerHookFireInRegistrationOrder() = runTest {
        val pipeline = PipelineBuilder()
        val seen = mutableListOf<String>()
        pipeline.register(MetricsPulseHook) { _: Name -> seen += "first" }
        pipeline.register(MetricsPulseHook) { _: Name -> seen += "second" }
        pipeline.register(MetricsPulseHook) { _: Name -> seen += "third" }

        pipeline.handlersOf(MetricsPulseHook).forEach { it("tick".asName()) }

        assertEquals(listOf("first", "second", "third"), seen)
    }

    @Test
    fun emptyRegistryReturnsEmptyHandlerList() = runTest {
        val pipeline = PipelineBuilder()
        assertEquals(emptyList(), pipeline.handlersOf(MetricsPulseHook))
    }
}
