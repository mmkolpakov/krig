@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.DevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private object SamplerFactoryContract : DeviceContractBuilder() {
    val sampled by property(MetaConverter.double, TypeIds.DOUBLE)
    val other by property(MetaConverter.double, TypeIds.DOUBLE)
    val mutable by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
    val command by action(MetaConverter.string, MetaConverter.string)
}

private object IncompatibleSamplerFactoryContract : DevicePropertyContract<Double> {
    override val name: Name = SamplerFactoryContract.sampled.name
    override val descriptor = SamplerFactoryContract.sampled.descriptor.copy(kind = PropertyKind.LOGICAL)
    override val converter: MetaConverter<Double> = MetaConverter.double
}

private fun assertSamplerFactoryDiagnostic(error: IllegalStateException) {
    val message = error.message.orEmpty()
    assertTrue(
        SamplerFactoryContract.sampled.name.toString() in message,
        "Factory diagnostic must identify the sampled property: $message",
    )
    assertTrue(message.contains("sampler", ignoreCase = true), "Factory diagnostic must identify the sampler: $message")
    assertTrue(message.contains("factory", ignoreCase = true), "Factory diagnostic must identify the factory: $message")
}

class DeviceBackendBuilderSamplerFactoryTest {
    @Test
    fun samePropertyReentrancyIsRejectedWithoutInstallingEitherSampler() {
        val builder = DeviceBackendBuilder()
        val outer = doubleSampler(capacity = 2)
        val inner = doubleSampler(capacity = 2)
        var innerFactoryCalled = false

        val error = assertFailsWith<IllegalStateException> {
            builder.sampler(SamplerFactoryContract.sampled) {
                builder.sampler(SamplerFactoryContract.sampled) {
                    innerFactoryCalled = true
                    inner
                }
                outer
            }
        }

        assertSamplerFactoryDiagnostic(error)
        assertFalse(innerFactoryCalled)
        assertNull(builder.build().sampler(SamplerFactoryContract.sampled))
    }

    @Test
    fun incompatiblePropertyRegistrationInsideFactoryIsRejectedAtomically() {
        val builder = DeviceBackendBuilder()
        val sampler = doubleSampler(capacity = 2)

        val error = assertFailsWith<IllegalStateException> {
            builder.sampler(SamplerFactoryContract.sampled) {
                builder.reader(IncompatibleSamplerFactoryContract) { 1.0 }
                sampler
            }
        }

        assertSamplerFactoryDiagnostic(error)
        val backend = builder.build()
        assertNull(backend.reader(IncompatibleSamplerFactoryContract))
        assertNull(backend.sampler(SamplerFactoryContract.sampled))
    }

    @Test
    fun factoryFailureLeavesBuilderReusable() {
        val builder = DeviceBackendBuilder()
        val failure = assertFailsWith<IllegalArgumentException> {
            builder.sampler(SamplerFactoryContract.sampled) {
                throw IllegalArgumentException("factory failed")
            }
        }
        assertEquals("factory failed", failure.message)

        val sampler = doubleSampler(capacity = 2)
        builder.sampler(SamplerFactoryContract.sampled) { sampler }

        assertSame(sampler, builder.build().sampler(SamplerFactoryContract.sampled))
    }

    @Test
    fun everyConfigurationRootIsRejectedDuringFactoryAndRemainsUsableAfterwards() {
        var nestedSamplerFactoryCalls = 0
        val attempts: List<Pair<String, DeviceBackendBuilder.() -> Unit>> = listOf(
            "reader" to { reader(SamplerFactoryContract.other) { 1.0 } },
            "observed reader" to { observedReader(SamplerFactoryContract.other) { error("must not run") } },
            "binary reader" to { binaryReader(SamplerFactoryContract.other) { error("must not run") } },
            "bytes reader" to { bytesReader(SamplerFactoryContract.other) { error("must not run") } },
            "writer" to { writer(SamplerFactoryContract.mutable) {} },
            "binding" to { bind(SamplerFactoryContract.mutable, read = { 1.0 }, write = {}) },
            "sampler" to {
                sampler(SamplerFactoryContract.other) {
                    nestedSamplerFactoryCalls += 1
                    doubleSampler(capacity = 2)
                }
            },
            "typed action" to { action(SamplerFactoryContract.command) { it } },
            "named readable cell" to { readable("factory-readable", 1.0, MetaConverter.double) },
            "typed readable cell" to { readable(SamplerFactoryContract.other, initial = 1.0) },
            "named writable cell" to { writable("factory-writable", 1.0, MetaConverter.double) },
            "typed writable cell" to { writable(SamplerFactoryContract.mutable, initial = 1.0) },
            "named computed cell" to { computed("factory-computed") { 1.0 } },
            "typed computed cell" to { computed(SamplerFactoryContract.other) { 1.0 } },
            "Meta action" to { action("factory-action") { null } },
            "typed Meta action" to { actionMeta(SamplerFactoryContract.command) { null } },
            "batch Meta reader" to { batchMetaReader { emptyMap() } },
            "batch observed reader" to { batchObservedReader { emptyMap() } },
            "batch binary reader" to { batchBinaryReader { emptyMap() } },
            "batch writer" to { batchWriter { emptyMap() } },
            "step callback" to { onStep {} },
            "close callback" to { onClose {} },
        )

        attempts.forEach { (name, attempt) ->
            val builder = DeviceBackendBuilder()
            val outerSampler = doubleSampler(capacity = 2)
            val error = assertFailsWith<IllegalStateException>("$name must not reconfigure a sampler factory") {
                builder.sampler(SamplerFactoryContract.sampled) {
                    builder.attempt()
                    outerSampler
                }
            }
            assertSamplerFactoryDiagnostic(error)
            if (name == "sampler") assertEquals(0, nestedSamplerFactoryCalls)

            builder.attempt()
            if (name == "sampler") assertEquals(1, nestedSamplerFactoryCalls)
            builder.sampler(SamplerFactoryContract.sampled) { outerSampler }
            assertSame(outerSampler, builder.build().sampler(SamplerFactoryContract.sampled))
        }

        assertEquals(1, nestedSamplerFactoryCalls, "The nested sampler factory must run only during the successful retry")
    }

    @Test
    fun buildIsRejectedDuringFactoryAndBuilderRemainsUsable() {
        val builder = DeviceBackendBuilder()
        val sampler = doubleSampler(capacity = 2)

        val error = assertFailsWith<IllegalStateException> {
            builder.sampler(SamplerFactoryContract.sampled) {
                builder.build().let { sampler }
            }
        }
        assertSamplerFactoryDiagnostic(error)

        builder.sampler(SamplerFactoryContract.sampled) { sampler }
        assertSame(sampler, builder.build().sampler(SamplerFactoryContract.sampled))
    }

    @Test
    fun factoryMayConfigureAnotherBuilder() {
        val outerBuilder = DeviceBackendBuilder()
        val independentBuilder = DeviceBackendBuilder()
        val sampler = doubleSampler(capacity = 2)

        outerBuilder.sampler(SamplerFactoryContract.sampled) {
            independentBuilder.reader(SamplerFactoryContract.other) { 2.0 }
            sampler
        }

        assertSame(sampler, outerBuilder.build().sampler(SamplerFactoryContract.sampled))
        assertNotNull(independentBuilder.build().reader(SamplerFactoryContract.other))
    }
}
