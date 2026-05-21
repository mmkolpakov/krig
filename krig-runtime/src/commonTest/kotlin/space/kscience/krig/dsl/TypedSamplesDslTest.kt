@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.dsl

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageEnvelope
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.SubscribeOptions
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

@OptIn(ExperimentalAtomicApi::class)
private val typedSamplesContextSeq: AtomicInt = AtomicInt(0)

@OptIn(ExperimentalAtomicApi::class)
private fun nextTypedSamplesContextName(): String =
    "typed-samples-test-${typedSamplesContextSeq.addAndFetch(1)}"

private class SamplerOnlyDevice : AbstractDevice(
    name = "sampler".asName(),
    runtime = DeviceRuntime(Context(nextTypedSamplesContextName())),
) {
    val sampler: RingDoubleSampler = doubleSampler(capacity = 16)

    val valueSpec: DevicePropertySpec<SamplerOnlyDevice, Double> =
        object : DevicePropertySpec<SamplerOnlyDevice, Double> {
            override val name: Name = "value".asName()
            override val descriptor: PropertyDescriptor =
                PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
            override val converter: MetaConverter<Double> = object : MetaConverter<Double> {
                override fun convert(obj: Double): Meta = Meta(obj)
                override fun readOrNull(source: Meta): Double =
                    error("typedSamples must use sampler flow before Meta: $source")
            }
            override suspend fun read(device: SamplerOnlyDevice): Double = error("typedSamples must not call read")
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        if (spec === valueSpec) sampler as TypedSampler<T> else null

    override suspend fun subscribe(principal: Principal): Flow<MessageEnvelope<DeviceMessage>> = messageFlow
    override suspend fun readProperty(propertyName: Name): Meta = error("typedSamples must not call readProperty")
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null

    suspend fun publish(value: Double) {
        sampler.publishDouble(value)
        emit(
            space.kscience.krig.api.messages.PropertyChangedMessage(
                time = Clock.System.now(),
                property = valueSpec.name,
                value = Meta(value),
                sourceDevice = name,
            ),
        )
    }
}

class TypedSamplesDslTest {

    @Test
    fun typedSamplesPrefersSamplerFlowWithoutMetaConversion() = runTest {
        val device = SamplerOnlyDevice()
        val samples = device.typedSamples(AnonymousPrincipal, device.valueSpec)

        val awaited = async(start = CoroutineStart.UNDISPATCHED) { samples.first() }
        device.publish(42.0)

        assertEquals(42.0, awaited.await())
    }

    @Test
    fun typedSamplesRejectsMessageTypeFilterOnValueFlow() = runTest {
        val device = SamplerOnlyDevice()

        assertFailsWith<IllegalArgumentException> {
            device.typedSamples(
                principal = AnonymousPrincipal,
                spec = device.valueSpec,
                options = SubscribeOptions(typeFilter = setOf("PropertyChangedMessage")),
            )
        }
    }
}
