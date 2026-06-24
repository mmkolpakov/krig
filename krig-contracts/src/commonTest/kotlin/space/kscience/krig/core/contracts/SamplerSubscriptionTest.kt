@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.requireDoubleSampler
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.*

private val samplerSeq: AtomicInt = AtomicInt(0)
private fun freshSamplerContext(): Context =
    Context("sampler-${samplerSeq.addAndFetch(1)}")

/**
 * End-to-end demonstration of the typed sampler contract on [SimulatedDoubleSource].
 *
 * - The driver publishes new values into a [RingDoubleSampler] on every typed write.
 * - Consumers obtain the sampler via `device.sampler(spec)`.
 * - Latest value is readable through the unboxed [RingDoubleSampler.latestDoubleOrNaN] path;
 *   streaming consumers attach via [RingDoubleSampler.flow].
 *
 * This is the canonical pattern for high-frequency drivers (Modbus polling loops, EPICS
 * monitor callbacks, simulation ticks) — they `publish(value)` from their producer
 * coroutine without going through the read pipeline.
 */
class SamplerSubscriptionTest {

    @Test
    fun samplerReturnsRingDoubleSampler() {
        val device = SimulatedDoubleSource(context = freshSamplerContext())
        val sampler = device.sampler(device.valueSpec)
        assertNotNull(sampler, "driver must expose a sampler for its known spec")
        assertIs<RingDoubleSampler>(sampler)
    }

    @Test
    fun samplerStartsEmpty_thenReportsLatestAfterPublish() = runTest {
        val device = SimulatedDoubleSource(context = freshSamplerContext())
        val sampler = device.requireDoubleSampler(device.valueSpec)

        assertFalse(sampler.hasLatest, "no value published yet")
        assertTrue(sampler.latestDoubleOrNaN().isNaN(), "empty primitive latest is NaN")

        val writer = device.writer(device.valueSpec)
        writer.write(42.0)
        assertTrue(sampler.hasLatest)
        assertEquals(42.0, sampler.latestDoubleOrNaN())

        writer.write(13.5)
        assertEquals(13.5, sampler.latestDoubleOrNaN())
    }

    @Test
    fun snapshotContainsPublishedValuesInOrder() = runTest {
        val device = SimulatedDoubleSource(context = freshSamplerContext())
        val sampler = device.requireDoubleSampler(device.valueSpec)
        val writer = device.writer(device.valueSpec)

        writer.write(1.0)
        writer.write(2.0)
        writer.write(3.0)

        assertEquals(listOf(1.0, 2.0, 3.0), sampler.snapshotDoubleArray().toList())
    }

    @Test
    fun flowEmitsEveryPublishedValue() = runTest {
        val device = SimulatedDoubleSource(context = freshSamplerContext())
        val sampler = device.requireDoubleSampler(device.valueSpec)
        val writer = device.writer(device.valueSpec)

        // Subscribe first; collect-then-write avoids dropping values on slow subscribers.
        val collector = async(start = CoroutineStart.UNDISPATCHED) { sampler.flow().take(3).toList() }
        writer.write(10.0)
        writer.write(20.0)
        writer.write(30.0)

        assertEquals(listOf(10.0, 20.0, 30.0), collector.await())
    }

    @Test
    fun samplerReturnsNullForUnknownSpec() {
        val device = SimulatedDoubleSource(context = freshSamplerContext())
        val alien = object : space.kscience.krig.core.meta.DevicePropertyContract<Double> {
            override val name = space.kscience.dataforge.names.Name.EMPTY
            override val descriptor = device.valueSpec.descriptor
            override val converter = device.valueSpec.converter
        }
        assertNull(device.sampler(alien), "drivers expose samplers only for owned specs")
    }
}
