@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.PerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.test.runTest
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.double
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val contextSeq: AtomicInt = AtomicInt(0)
@Suppress("SameParameterValue")
private fun freshContext(prefix: String): Context = Context("$prefix-${contextSeq.addAndFetch(1)}")

/**
 * Conformance test for [SimulatedDoubleSource] — the in-tree reference fixture for typed
 * reader / writer overrides on [AbstractDevice]. The fast typed path and the Meta-boxed
 * fallback share the same cell; a regression that makes them diverge is exactly the silent
 * failure the `@SubclassOptInRequired(UnstableKrigForSubclassing::class)` marker on
 * [Device][space.kscience.krig.core.contracts.Device] warns driver authors about.
 */
class SimulatedDoubleSourceTest {

    @Test
    fun typedReaderReturnsGenericTypedReader() = runTest {
        val device = SimulatedDoubleSource(context = freshContext("simulated-double-source"))
        val reader = device.reader(device.valueSpec)
        assertIs<GenericTypedReader<Double>>(reader)
    }

    @Test
    fun typedWriterReturnsGenericTypedWriter() = runTest {
        val device = SimulatedDoubleSource(context = freshContext("simulated-double-source"))
        val writer = device.writer(device.valueSpec)
        assertIs<GenericTypedWriter<Double>>(writer)
    }

    @Test
    fun typedAndMetaPathsShareTheSameCell() = runTest {
        val device = SimulatedDoubleSource(context = freshContext("simulated-double-source"))
        val writer = device.writer(device.valueSpec) as GenericTypedWriter<Double>
        val reader = device.reader(device.valueSpec) as GenericTypedReader<Double>

        // Write via typed path, read via Meta path.
        writer.write(42.0)
        val metaRead = device.readProperty(device.valueSpec.name).double
        assertEquals(42.0, metaRead)

        // Write via Meta path, read via typed path — same cell, same value.
        device.writeProperty(device.valueSpec.name, space.kscience.dataforge.meta.Meta(13.5))
        assertEquals(13.5, reader.read())
    }
}
