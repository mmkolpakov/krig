@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class CountingPipelineDevice : AbstractDevice(
    name = "counting".asName(),
    runtime = DeviceRuntime(Context("typed-pipeline-cache-${contextSeq.incrementAndGet()}")),
) {
    val readerBuilds = atomic(0)
    val writerBuilds = atomic(0)
    val readCalls = atomic(0)
    val writeCalls = atomic(0)
    val actionCalls = atomic(0)
    val shutdownCalls = atomic(0)

    val valueSpec = object : MutableDevicePropertySpec<CountingPipelineDevice, Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = MetaConverter.double
        override suspend fun read(device: CountingPipelineDevice): Double = 1.0
        override suspend fun write(device: CountingPipelineDevice, value: Double) = Unit
    }

    val actionSpec = object : DeviceActionSpec<CountingPipelineDevice, Int, Int> {
        override val name: Name = "inc".asName()
        override val descriptor: ActionDescriptor = ActionDescriptor(name)
        override val inputConverter: MetaConverter<Int> = MetaConverter.int
        override val outputConverter: MetaConverter<Int> = MetaConverter.int
        override suspend fun execute(device: CountingPipelineDevice, input: Int): Int = input + 1
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertySpec<*, T>): TypedReader<T> {
        readerBuilds.incrementAndGet()
        return GenericTypedReader {
            readCalls.incrementAndGet()
            42.0 as T
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertySpec<*, T>): TypedWriter<T> {
        writerBuilds.incrementAndGet()
        return GenericTypedWriter {
            writeCalls.incrementAndGet()
        }
    }

    override suspend fun readProperty(propertyName: Name): Meta = MetaConverter.double.convert(42.0)
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    override suspend fun execute(actionName: Name, argument: Meta?): Meta {
        actionCalls.incrementAndGet()
        val value = argument?.int ?: 0
        return MetaConverter.int.convert(value + 1)
    }

    override suspend fun shutdown() {
        shutdownCalls.incrementAndGet()
    }
}

private val contextSeq = atomic(0)

private class BadEncodeDevice : AbstractDevice(
    name = "bad-encode".asName(),
    runtime = DeviceRuntime(Context("typed-pipeline-bad-encode-${contextSeq.incrementAndGet()}")),
) {
    val badSpec = object : DevicePropertySpec<BadEncodeDevice, Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = object : MetaConverter<Double> {
            override fun convert(obj: Double): Meta = throw ClassCastException("bad encode")
            override fun readOrNull(source: Meta): Double? = MetaConverter.double.readOrNull(source)
        }

        override suspend fun read(device: BadEncodeDevice): Double = 1.0
    }

    override fun propertySpec(propertyName: Name): DevicePropertySpec<*, *>? =
        if (propertyName == badSpec.name) badSpec else null

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertySpec<*, T>): TypedReader<T> =
        GenericTypedReader { 1.0 as T }

    override suspend fun readProperty(propertyName: Name): Meta = Meta.EMPTY
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
}

class TypedPipelineDeviceCacheTest {
    @Test
    fun readerAndWriterHandlesAreCompiledOncePerProperty() = runTest {
        val delegate = CountingPipelineDevice()
        var readObserved = 0
        var writeObserved = 0
        val device = TypedPipelineDevice(
            delegate = delegate,
            readSpec = ReadPipelineSpec(observers = listOf(ReadObserver { _, _, _ -> readObserved++ })),
            writeSpec = WritePipelineSpec(observers = listOf(WriteObserver { _, _, _ -> writeObserved++ })),
        )

        assertEquals(42.0, device.reader(delegate.valueSpec).read())
        assertEquals(42.0, device.reader(delegate.valueSpec).read())
        device.writer(delegate.valueSpec).write(1.0)
        device.writer(delegate.valueSpec).write(2.0)

        assertEquals(1, delegate.readerBuilds.value)
        assertEquals(1, delegate.writerBuilds.value)
        assertEquals(2, delegate.readCalls.value)
        assertEquals(2, delegate.writeCalls.value)
        assertEquals(2, readObserved)
        assertEquals(2, writeObserved)
    }

    @Test
    fun actionExecutorIsReusedAndObserversStillFirePerCall() = runTest {
        val delegate = CountingPipelineDevice()
        var actionObserved = 0
        val device = TypedPipelineDevice(
            delegate = delegate,
            actionSpec = ActionPipelineSpec(observers = listOf(ActionObserver { _, _, _ -> actionObserved++ })),
        )

        assertEquals(2, device.action(delegate.actionSpec).execute(1))
        assertEquals(3, device.action(delegate.actionSpec).execute(2))

        assertEquals(2, delegate.actionCalls.value)
        assertEquals(2, actionObserved)
    }

    @Test
    fun operationAccountingWrapsTheWholeReadPipeline() = runTest {
        val delegate = CountingPipelineDevice()
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val device = TypedPipelineDevice(
            delegate = delegate,
            readSpec = ReadPipelineSpec(
                gates = listOf(ReadGate {
                    gateEntered.complete(Unit)
                    releaseGate.await()
                }),
            ),
        )

        val readJob = launch {
            assertEquals(42.0, device.reader(delegate.valueSpec).read())
        }
        gateEntered.await()

        val closeJob = launch {
            delegate.closeGracefully(1.seconds)
        }
        runCurrent()
        assertTrue(closeJob.isActive, "closeGracefully must wait while a read is still in a gate")

        releaseGate.complete(Unit)
        runCurrent()

        assertTrue(readJob.isCompleted)
        assertTrue(closeJob.isCompleted)
        assertEquals(1, delegate.shutdownCalls.value)
    }

    @Test
    fun sameNameWithDifferentConverterIsRejected() {
        val delegate = CountingPipelineDevice()
        val device = TypedPipelineDevice(delegate = delegate)
        val cached = device.reader(delegate.valueSpec)
        assertEquals(cached, cached)
        val conflicting = object : DevicePropertySpec<CountingPipelineDevice, Double> by delegate.valueSpec {
            override val converter: MetaConverter<Double> = object : MetaConverter<Double> {
                override fun convert(obj: Double): Meta = MetaConverter.double.convert(obj)
                override fun readOrNull(source: Meta): Double? = MetaConverter.double.readOrNull(source)
            }
        }

        assertFailsWith<IllegalStateException> {
            device.reader(conflicting)
        }
    }

    @Test
    fun controlPlaneReadEncodeFailuresBecomeValidationFaults() = runTest {
        val delegate = BadEncodeDevice()
        val device = TypedPipelineDevice(delegate = delegate)

        val failure = assertFailsWith<DeviceFaultException> {
            device.readProperty(delegate.badSpec.name)
        }

        assertTrue(failure.fault is ValidationFault)
        assertTrue(failure.cause is ClassCastException)
    }
}
