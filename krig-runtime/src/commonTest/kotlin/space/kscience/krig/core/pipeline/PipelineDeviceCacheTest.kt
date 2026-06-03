@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.pipeline

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    val batchReadCalls = atomic(0)
    val batchWriteCalls = atomic(0)
    val actionCalls = atomic(0)
    val shutdownCalls = atomic(0)
    val operationEntries = atomic(0)
    val operationExits = atomic(0)
    val valueSampler = RingDoubleSampler(capacity = 4)

    val valueSpec = object : MutableDevicePropertyContract<Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = MetaConverter.double
    }

    val actionSpec = object : DeviceActionContract<Int, Int> {
        override val name: Name = "inc".asName()
        override val descriptor: ActionDescriptor = ActionDescriptor(name)
        override val inputConverter: MetaConverter<Int> = MetaConverter.int
        override val outputConverter: MetaConverter<Int> = MetaConverter.int
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> {
        readerBuilds.incrementAndGet()
        return TypedReader {
            readCalls.incrementAndGet()
            42.0 as T
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> {
        writerBuilds.incrementAndGet()
        return TypedWriter {
            writeCalls.incrementAndGet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        if (spec.name == valueSpec.name) valueSampler as TypedSampler<T> else null

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
        if (propertyName == valueSpec.name) valueSpec else null

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(MetaConverter.double.convert(42.0))

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doReadBatchOutcome(
        properties: Collection<Name>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        batchReadCalls.incrementAndGet()
        return properties.associateWith {
            OperationOutcome.Ok(ObservedValue(MetaConverter.double.convert(42.0), clock.now(), DataQuality.GOOD))
        }
    }

    override suspend fun doWriteBatchOutcome(values: Map<Name, Meta>): Map<Name, OperationOutcome<Unit>> {
        batchWriteCalls.incrementAndGet()
        return values.keys.associateWith { OperationOutcome.OkUnit }
    }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(
            MetaConverter.int.convert((argument?.int ?: 0) + 1).also {
                actionCalls.incrementAndGet()
            },
        )

    override suspend fun shutdown() {
        shutdownCalls.incrementAndGet()
    }

    override fun enterOperation() {
        operationEntries.incrementAndGet()
        super.enterOperation()
    }

    override fun exitOperation() {
        operationExits.incrementAndGet()
        super.exitOperation()
    }
}

private val contextSeq = atomic(0)

private class BadEncodeDevice : AbstractDevice(
    name = "bad-encode".asName(),
    runtime = DeviceRuntime(Context("typed-pipeline-bad-encode-${contextSeq.incrementAndGet()}")),
) {
    val badSpec = object : DevicePropertyContract<Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = object : MetaConverter<Double> {
            override fun convert(obj: Double): Meta = throw ClassCastException("bad encode: $obj")
            override fun readOrNull(source: Meta): Double? = MetaConverter.double.readOrNull(source)
        }
    }

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
        if (propertyName == badSpec.name) badSpec else null

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        TypedReader { 1.0 as T }

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

private class CancellableControlPlaneDevice : AbstractDevice(
    name = "cancel-control-plane".asName(),
    runtime = DeviceRuntime(Context("typed-pipeline-cancel-${contextSeq.incrementAndGet()}")),
) {
    val readStarted = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    val valueSpec = object : DevicePropertyContract<Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = MetaConverter.double
    }

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
        if (propertyName == valueSpec.name) valueSpec else null

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        TypedReader {
            readStarted.complete(Unit)
            never.await()
            1.0 as T
        }

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

class PipelineDeviceCacheTest {
    @Test
    fun readerAndWriterHandlesAreCompiledOncePerProperty() = runTest {
        val delegate = CountingPipelineDevice()
        var readObserved = 0
        var writeObserved = 0
        val device = PipelineDevice(
            delegate = delegate,
            operationSpecs = mapOf(
                OperationKinds.Read to OperationPipelineSpec(observers = listOf(OperationObserver { _, _, _ ->
                    readObserved++
                })),
                OperationKinds.Write to OperationPipelineSpec(observers = listOf(OperationObserver { _, _, _ ->
                    writeObserved++
                })),
            ),
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
        val device = PipelineDevice(
            delegate = delegate,
            operationSpecs = mapOf(
                OperationKinds.Action to OperationPipelineSpec(observers = listOf(OperationObserver { _, _, _ ->
                    actionObserved++
                })),
            ),
        )

        assertEquals(2, device.action(delegate.actionSpec).execute(1))
        assertEquals(3, device.action(delegate.actionSpec).execute(2))

        assertEquals(2, delegate.actionCalls.value)
        assertEquals(2, actionObserved)
    }

    @Test
    fun samplerBypassesOperationAccounting() = runTest {
        val delegate = CountingPipelineDevice()
        val device = PipelineDevice(delegate = delegate)

        val sampler = assertIs<RingDoubleSampler>(device.sampler(delegate.valueSpec))
        sampler.publishDouble(7.0)

        assertEquals(7.0, sampler.latestDoubleOrNaN())
        assertEquals(1, sampler.snapshotDoubleArray().size)
        assertEquals(0, delegate.operationEntries.value)
        assertEquals(0, delegate.operationExits.value)

        assertEquals(42.0, device.reader(delegate.valueSpec).read())

        assertEquals(1, delegate.operationEntries.value)
        assertEquals(1, delegate.operationExits.value)
    }

    @Test
    fun operationAccountingWrapsWholeReadOperation() = runTest {
        val delegate = CountingPipelineDevice()
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val device = PipelineDevice(
            delegate = delegate,
            operationSpecs = mapOf(
                OperationKinds.Read to OperationPipelineSpec(
                    gates = listOf(OperationGate {
                    gateEntered.complete(Unit)
                    releaseGate.await()
                    okUnit()
                    }),
                ),
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
    fun batchReadOutcomePassesThroughPipeline() = runTest {
        val delegate = CountingPipelineDevice()
        val gateNames = mutableListOf<Name>()
        var readObserved = 0
        val device = PipelineDevice(
            delegate = delegate,
            operationSpecs = mapOf(
                OperationKinds.Read to OperationPipelineSpec(
                    gates = listOf(OperationGate { context ->
                        gateNames += context.name
                        okUnit()
                    }),
                    observers = listOf(OperationObserver { _, _, _ -> readObserved++ }),
                ),
            ),
        )

        val outcome = device.readBatchOutcome(listOf(delegate.valueSpec.name)).getValue(delegate.valueSpec.name)

        assertTrue(outcome is OperationOutcome.Ok)
        assertEquals(1, delegate.batchReadCalls.value)
        assertTrue(delegate.valueSpec.name in gateNames)
        assertTrue(OperationNames.BatchRead in gateNames)
        assertEquals(1, readObserved)
    }

    @Test
    fun batchWriteOutcomePassesThroughPipeline() = runTest {
        val delegate = CountingPipelineDevice()
        val gateNames = mutableListOf<Name>()
        var writeObserved = 0
        val device = PipelineDevice(
            delegate = delegate,
            operationSpecs = mapOf(
                OperationKinds.Write to OperationPipelineSpec(
                    gates = listOf(OperationGate { context ->
                        gateNames += context.name
                        okUnit()
                    }),
                    observers = listOf(OperationObserver { _, _, _ -> writeObserved++ }),
                ),
            ),
        )

        val outcome = device.writeBatchOutcome(
            mapOf(delegate.valueSpec.name to MetaConverter.double.convert(9.0)),
        ).getValue(delegate.valueSpec.name)

        assertTrue(outcome is OperationOutcome.Ok)
        assertEquals(1, delegate.batchWriteCalls.value)
        assertTrue(delegate.valueSpec.name in gateNames)
        assertTrue(OperationNames.BatchWrite in gateNames)
        assertEquals(1, writeObserved)
    }

    @Test
    fun sameNameWithDifferentConverterIsRejected() {
        val delegate = CountingPipelineDevice()
        val device = PipelineDevice(delegate = delegate)
        val cached = device.reader(delegate.valueSpec)
        assertEquals(cached, cached)
        val conflicting = object : DevicePropertyContract<Double> by delegate.valueSpec {
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
        val device = PipelineDevice(delegate = delegate)

        val failure = device.readPropertyOutcome(delegate.badSpec.name)

        assertTrue(failure is OperationOutcome.Fail)
        val fault = failure.fault
        assertTrue(fault is ValidationFault)
        assertEquals("ClassCastException", fault.details["causeType".asName()]?.string)
    }

    @Test
    fun controlPlaneCancellationDoesNotMarkDeviceFailed() = runTest {
        val delegate = CancellableControlPlaneDevice()
        val device = PipelineDevice(delegate = delegate)

        val job = launch {
            val outcome = device.readPropertyOutcome(delegate.valueSpec.name)
            error("read completed unexpectedly: $outcome")
        }
        delegate.readStarted.await()
        job.cancelAndJoin()

        assertTrue(delegate.lifecycleState !is LifecycleState.Failed)
    }
}
