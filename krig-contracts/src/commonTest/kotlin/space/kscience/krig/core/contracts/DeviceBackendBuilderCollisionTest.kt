@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

private object CollisionContract : DeviceContractBuilder() {
    val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
    val command by action(MetaConverter.string, MetaConverter.string)
}

private object ReadOnlyCollisionContract : DeviceContractBuilder() {
    val value by property(MetaConverter.double, TypeIds.DOUBLE)
}

private object IncompatibleValueContract : MutableDevicePropertyContract<Double> {
    override val name: Name = CollisionContract.value.name
    override val descriptor = CollisionContract.value.descriptor.copy(kind = PropertyKind.LOGICAL)
    override val converter: MetaConverter<Double> = MetaConverter.double
}

private object IncompatibleReadOnlyValueContract : DevicePropertyContract<Double> {
    override val name: Name = ReadOnlyCollisionContract.value.name
    override val descriptor = ReadOnlyCollisionContract.value.descriptor.copy(kind = PropertyKind.LOGICAL)
    override val converter: MetaConverter<Double> = MetaConverter.double
}

private object CompatibleReadOnlyValueAlias : DevicePropertyContract<Double> {
    override val name: Name = ReadOnlyCollisionContract.value.name
    override val descriptor = ReadOnlyCollisionContract.value.descriptor
    override val converter: MetaConverter<Double> = ReadOnlyCollisionContract.value.converter
}

private object ReadOnlyContractWithMutableDescriptor : DevicePropertyContract<Double> {
    override val name: Name = CollisionContract.value.name
    override val descriptor = CollisionContract.value.descriptor
    override val converter: MetaConverter<Double> = MetaConverter.double
}

private object MutableContractWithReadOnlyDescriptor : MutableDevicePropertyContract<Double> {
    override val name: Name = CollisionContract.value.name
    override val descriptor = ReadOnlyCollisionContract.value.descriptor
    override val converter: MetaConverter<Double> = MetaConverter.double
}

private object SameNameActionContract : DeviceActionContract<String, String> {
    override val name: Name = CollisionContract.value.name
    override val descriptor: ActionDescriptor = ActionDescriptor(name)
    override val inputConverter: MetaConverter<String> = MetaConverter.string
    override val outputConverter: MetaConverter<String> = MetaConverter.string
}

private object CustomComputedContract : DevicePropertyContract<Double> {
    override val name: Name = CollisionContract.value.name
    override val descriptor = ReadOnlyCollisionContract.value.descriptor
    override val converter: MetaConverter<Double> = object : MetaConverter<Double> {
        override fun convert(obj: Double) = MetaConverter.double.convert(obj + 100.0)

        override fun readOrNull(source: Meta): Double? =
            MetaConverter.double.readOrNull(source)?.minus(100.0)
    }
}

private enum class ActionRegistration {
    TYPED,
    RAW,
    META,
    ;

    fun register(builder: DeviceBackendBuilder, result: String) {
        when (this) {
            TYPED -> builder.action(CollisionContract.command) { "$result:$it" }
            RAW -> builder.action(CollisionContract.command.name.toString()) { metaOf(result) }
            META -> builder.actionMeta(CollisionContract.command) { metaOf(result) }
        }
    }

    fun expectedResult(result: String, argument: String): String =
        if (this == TYPED) "$result:$argument" else result
}

private fun DeviceBackend.bindCollisionStub(): BoundDeviceBackend =
    bind(BackendEnvironment.from(testRuntime("backend-collision"), CollisionContract.value.name))

private fun assertCollisionDiagnostic(error: IllegalStateException, name: Name, operation: String) {
    val message = error.message.orEmpty()
    assertTrue(name.toString() in message, "Collision diagnostic must identify '$name': $message")
    assertTrue(
        message.contains(operation, ignoreCase = true),
        "Collision diagnostic must identify the $operation lane: $message",
    )
}

class DeviceBackendBuilderCollisionTest {
    @Test
    fun duplicateWriterIsRejectedWithoutReplacingTheFirstHandler() = runTest {
        var written = 0.0
        val builder = DeviceBackendBuilder()
        builder.writer(CollisionContract.value) { written = it }

        val error = assertFailsWith<IllegalStateException> {
            builder.writer(CollisionContract.value) { written = -it }
        }
        assertCollisionDiagnostic(error, CollisionContract.value.name, "writer")

        val writer = assertNotNull(builder.build().writer(CollisionContract.value))
        writer.write(4.0)
        assertEquals(4.0, written)
    }

    @Test
    fun bindRejectsWriterConflictBeforeInstallingReader() = runTest {
        var written = 0.0
        val builder = DeviceBackendBuilder()
        builder.writer(CollisionContract.value) { written = it }

        val error = assertFailsWith<IllegalStateException> {
            builder.bind(
                CollisionContract.value,
                read = { -1.0 },
                write = { written = -it },
            )
        }
        assertCollisionDiagnostic(error, CollisionContract.value.name, "writer")

        val backend = builder.build()
        assertNull(backend.reader(CollisionContract.value))
        val writer = assertNotNull(backend.writer(CollisionContract.value))
        writer.write(4.0)
        assertEquals(4.0, written)
    }

    @Test
    fun bindRejectsReaderConflictBeforeInstallingWriter() = runTest {
        val builder = DeviceBackendBuilder()
        builder.reader(CollisionContract.value) { 3.0 }

        val error = assertFailsWith<IllegalStateException> {
            builder.bind(
                CollisionContract.value,
                read = { -1.0 },
                write = {},
            )
        }
        assertCollisionDiagnostic(error, CollisionContract.value.name, "reader")

        val backend = builder.build()
        assertEquals(3.0, assertNotNull(backend.reader(CollisionContract.value)).read())
        assertNull(backend.writer(CollisionContract.value))
    }

    @Test
    fun bindRegistersReaderAndWriterAsOneDeclaration() = runTest {
        for (samplerFirst in listOf(true, false)) {
            var value = 1.0
            val sampler = doubleSampler(capacity = 2)
            val builder = DeviceBackendBuilder()
            if (samplerFirst) builder.sampler(CollisionContract.value) { sampler }
            builder.bind(
                CollisionContract.value,
                read = { value },
                write = { value = it },
            )
            if (!samplerFirst) builder.sampler(CollisionContract.value) { sampler }
            val backend = builder.build()

            val reader = assertNotNull(backend.reader(CollisionContract.value))
            val writer = assertNotNull(backend.writer(CollisionContract.value))
            assertSame(sampler, backend.sampler(CollisionContract.value))
            assertSame(CollisionContract.value, backend.propertySpec(CollisionContract.value.name))
            assertSame(
                CollisionContract.value,
                backend.propertySpecs()[CollisionContract.value.name],
            )
            assertEquals(1.0, reader.read())

            val bound = backend.bindCollisionStub()
            assertEquals(1.0, bound.read(CollisionContract.value.descriptor).doubleValue)
            bound.write(CollisionContract.value.descriptor, metaOf(7.0))
            assertEquals(7.0, reader.read())
            writer.write(9.0)
            assertEquals(9.0, bound.read(CollisionContract.value.descriptor).doubleValue)
        }
    }

    @Test
    fun duplicateSamplerIsRejectedBeforeItsFactoryRuns() {
        val first = doubleSampler(capacity = 2)
        val builder = DeviceBackendBuilder()
        builder.sampler(CollisionContract.value) { first }
        var secondFactoryCalled = false

        val error = assertFailsWith<IllegalStateException> {
            builder.sampler(CollisionContract.value) {
                secondFactoryCalled = true
                doubleSampler(capacity = 2)
            }
        }
        assertCollisionDiagnostic(error, CollisionContract.value.name, "sampler")

        assertFalse(secondFactoryCalled)
        assertSame(first, builder.build().sampler(CollisionContract.value))
    }

    @Test
    fun actionRegistrationsAreMutuallyExclusiveAndKeepTheFirstHandler() = runTest {
        for (first in ActionRegistration.entries) {
            for (second in ActionRegistration.entries) {
                val builder = DeviceBackendBuilder()
                first.register(builder, "first")

                val error = assertFailsWith<IllegalStateException> {
                    second.register(builder, "second")
                }
                assertCollisionDiagnostic(error, CollisionContract.command.name, "action")

                val backend = builder.build()
                val typed = backend.action(CollisionContract.command)
                if (first == ActionRegistration.TYPED) {
                    assertNotNull(typed)
                    assertSame(CollisionContract.command, backend.actionSpec(CollisionContract.command.name))
                    assertEquals("first:go", typed.execute("go"))
                } else {
                    assertNull(typed)
                    assertNull(backend.actionSpec(CollisionContract.command.name))
                }
                assertEquals(
                    first.expectedResult("first", "go"),
                    assertNotNull(
                        backend.bindCollisionStub().execute(CollisionContract.command.descriptor, metaOf("go")),
                    ).stringValue,
                    "$first must remain installed after rejecting $second",
                )
            }
        }
    }

    @Test
    fun cellAndContractWriterCannotDivergeInEitherOrder() = runTest {
        var written = 0.0
        val typedFirst = DeviceBackendBuilder()
        typedFirst.writer(CollisionContract.value) { written = it }
        assertFailsWith<IllegalStateException> {
            typedFirst.writable(CollisionContract.value, initial = 1.0)
        }
        val typedBackend = typedFirst.build()
        typedBackend.bindCollisionStub().write(CollisionContract.value.descriptor, metaOf(3.0))
        assertEquals(3.0, written)

        val cellFirst = DeviceBackendBuilder()
        val cell = cellFirst.writable(CollisionContract.value, initial = 1.0)
        assertFailsWith<IllegalStateException> {
            cellFirst.writer(CollisionContract.value) { written = -it }
        }
        val cellBackend = cellFirst.build()
        assertNull(cellBackend.writer(CollisionContract.value))
        cellBackend.bindCollisionStub().write(CollisionContract.value.descriptor, metaOf(5.0))
        assertEquals(5.0, cell.value)
    }

    @Test
    fun typedCellsComposeWithCompatibleSamplersInEitherOrder() = runTest {
        val sampler = doubleSampler(capacity = 2)
        val cellFirst = DeviceBackendBuilder()
        val readable = cellFirst.readable(ReadOnlyCollisionContract.value, initial = 1.0)
        cellFirst.sampler(CompatibleReadOnlyValueAlias) { sampler }
        val readableBackend = cellFirst.build()
        assertSame(sampler, readableBackend.sampler(CompatibleReadOnlyValueAlias))
        assertSame(
            ReadOnlyCollisionContract.value,
            readableBackend.propertySpec(ReadOnlyCollisionContract.value.name),
        )
        assertSame(
            ReadOnlyCollisionContract.value,
            readableBackend.propertySpecs()[ReadOnlyCollisionContract.value.name],
        )
        assertEquals(
            readable.value,
            readableBackend.bindCollisionStub().read(ReadOnlyCollisionContract.value.descriptor).doubleValue,
        )

        val samplerFirst = DeviceBackendBuilder()
        samplerFirst.sampler(CompatibleReadOnlyValueAlias) { sampler }
        val secondReadable = samplerFirst.readable(ReadOnlyCollisionContract.value, initial = 2.0)
        val samplerBackend = samplerFirst.build()
        assertSame(sampler, samplerBackend.sampler(CompatibleReadOnlyValueAlias))
        assertSame(
            ReadOnlyCollisionContract.value,
            samplerBackend.propertySpec(ReadOnlyCollisionContract.value.name),
        )
        assertSame(
            ReadOnlyCollisionContract.value,
            samplerBackend.propertySpecs()[ReadOnlyCollisionContract.value.name],
        )
        assertEquals(
            secondReadable.value,
            samplerBackend.bindCollisionStub().read(ReadOnlyCollisionContract.value.descriptor).doubleValue,
        )
    }

    @Test
    fun typedCellsExposeTheirContractsWhileKeepingMetaFallback() = runTest {
        val readableBackend = deviceBackend {
            readable(ReadOnlyCollisionContract.value, initial = 1.0)
        }
        assertSame(
            ReadOnlyCollisionContract.value,
            readableBackend.propertySpec(ReadOnlyCollisionContract.value.name),
        )
        assertEquals(
            mapOf(ReadOnlyCollisionContract.value.name to ReadOnlyCollisionContract.value),
            readableBackend.propertySpecs(),
        )
        assertNull(readableBackend.reader(ReadOnlyCollisionContract.value))

        val writableBuilder = DeviceBackendBuilder()
        val cell = writableBuilder.writable(CollisionContract.value, initial = 2.0)
        val writableBackend = writableBuilder.build()
        assertSame(CollisionContract.value, writableBackend.propertySpec(CollisionContract.value.name))
        assertEquals(
            mapOf(CollisionContract.value.name to CollisionContract.value),
            writableBackend.propertySpecs(),
        )
        assertNull(writableBackend.reader(CollisionContract.value))
        assertNull(writableBackend.writer(CollisionContract.value))

        writableBackend.bindCollisionStub().write(CollisionContract.value.descriptor, metaOf(3.0))
        assertEquals(3.0, cell.value)
    }

    @Test
    fun cellContractsRejectContradictoryMutabilityBeforeMutation() {
        val readableBuilder = DeviceBackendBuilder()
        assertFailsWith<IllegalArgumentException> {
            readableBuilder.readable(CollisionContract.value, initial = 1.0)
        }
        readableBuilder.writable(CollisionContract.value, initial = 2.0)

        val computedBuilder = DeviceBackendBuilder()
        assertFailsWith<IllegalArgumentException> {
            computedBuilder.computed(CollisionContract.value) { 1.0 }
        }
        computedBuilder.readable(ReadOnlyCollisionContract.value, initial = 2.0)

        val malformedReadOnlyBuilder = DeviceBackendBuilder()
        assertFailsWith<IllegalArgumentException> {
            malformedReadOnlyBuilder.readable(ReadOnlyContractWithMutableDescriptor, initial = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            malformedReadOnlyBuilder.computed(ReadOnlyContractWithMutableDescriptor) { 1.0 }
        }
        malformedReadOnlyBuilder.readable(ReadOnlyCollisionContract.value, initial = 2.0)

        val malformedMutableBuilder = DeviceBackendBuilder()
        assertFailsWith<IllegalArgumentException> {
            malformedMutableBuilder.writable(MutableContractWithReadOnlyDescriptor, initial = 1.0)
        }
        malformedMutableBuilder.writable(CollisionContract.value, initial = 2.0)
    }

    @Test
    fun computedTypedCellUsesItsContractConverterWithSamplerInEitherOrder() = runTest {
        val sampler = doubleSampler(capacity = 2)
        val cellFirst = deviceBackend {
            computed(CustomComputedContract) { 3.0 }
            sampler(CustomComputedContract) { sampler }
        }
        assertEquals(
            103.0,
            cellFirst.bindCollisionStub().read(CustomComputedContract.descriptor).doubleValue,
        )

        val samplerFirst = deviceBackend {
            sampler(CustomComputedContract) { sampler }
            computed(CustomComputedContract) { 4.0 }
        }
        assertEquals(
            104.0,
            samplerFirst.bindCollisionStub().read(CustomComputedContract.descriptor).doubleValue,
        )
    }

    @Test
    fun untypedOrIncompatibleCellsCannotClaimASamplerContract() {
        val sampler = doubleSampler(capacity = 2)
        val rawCellFirst = DeviceBackendBuilder()
        rawCellFirst.readable(CollisionContract.value.name.toString(), 1.0, MetaConverter.double)
        assertFailsWith<IllegalStateException> {
            rawCellFirst.sampler(CollisionContract.value) { sampler }
        }

        val samplerFirst = DeviceBackendBuilder()
        samplerFirst.sampler(CollisionContract.value) { sampler }
        assertFailsWith<IllegalStateException> {
            samplerFirst.readable(CollisionContract.value.name.toString(), 1.0, MetaConverter.double)
        }

        val typedCellFirst = DeviceBackendBuilder()
        typedCellFirst.readable(ReadOnlyCollisionContract.value, initial = 1.0)
        assertFailsWith<IllegalStateException> {
            typedCellFirst.sampler(IncompatibleReadOnlyValueContract) { sampler }
        }

        val incompatibleSamplerFirst = DeviceBackendBuilder()
        incompatibleSamplerFirst.sampler(IncompatibleReadOnlyValueContract) { sampler }
        assertFailsWith<IllegalStateException> {
            incompatibleSamplerFirst.readable(ReadOnlyCollisionContract.value, initial = 1.0)
        }
    }

    @Test
    fun computedCellCannotDivergeFromAContractWriter() = runTest {
        val computedFirst = DeviceBackendBuilder()
        val computed = computedFirst.computed(ReadOnlyCollisionContract.value) { 3.0 }
        assertFailsWith<IllegalStateException> {
            computedFirst.writer(CollisionContract.value) { }
        }
        val computedBackend = computedFirst.build()
        assertNull(computedBackend.writer(CollisionContract.value))
        assertSame(
            ReadOnlyCollisionContract.value,
            computedBackend.propertySpec(ReadOnlyCollisionContract.value.name),
        )
        assertEquals(
            computed.value,
            computedBackend.bindCollisionStub().read(ReadOnlyCollisionContract.value.descriptor).doubleValue,
        )
    }

    @Test
    fun propertyCapabilitiesRequireOneCompatibleContractAndKeepTheFirstHandler() = runTest {
        val readerFirst = DeviceBackendBuilder()
        readerFirst.reader(CollisionContract.value) { 1.0 }
        assertFailsWith<IllegalStateException> {
            readerFirst.writer(IncompatibleValueContract) { }
        }
        val readerBackend = readerFirst.build()
        assertEquals(1.0, readerBackend.reader(CollisionContract.value)?.read())
        assertNull(readerBackend.writer(IncompatibleValueContract))
        assertSame(CollisionContract.value, readerBackend.propertySpec(CollisionContract.value.name))

        var written = 0.0
        val writerFirst = DeviceBackendBuilder()
        writerFirst.writer(IncompatibleValueContract) { written = it }
        assertFailsWith<IllegalStateException> {
            writerFirst.reader(CollisionContract.value) { 1.0 }
        }
        val writerBackend = writerFirst.build()
        assertNull(writerBackend.reader(CollisionContract.value))
        writerBackend.writer(IncompatibleValueContract)?.write(4.0)
        assertEquals(4.0, written)
        assertSame(IncompatibleValueContract, writerBackend.propertySpec(CollisionContract.value.name))
    }

    @Test
    fun samplersRequireTheSameContractAsOtherPropertyCapabilities() = runTest {
        val readerFirst = DeviceBackendBuilder()
        readerFirst.reader(CollisionContract.value) { 1.0 }
        assertFailsWith<IllegalStateException> {
            readerFirst.sampler(IncompatibleValueContract) { doubleSampler(capacity = 2) }
        }
        val readerBackend = readerFirst.build()
        assertEquals(1.0, readerBackend.reader(CollisionContract.value)?.read())
        assertNull(readerBackend.sampler(IncompatibleValueContract))

        val sampler = doubleSampler(capacity = 2)
        val samplerFirst = DeviceBackendBuilder()
        samplerFirst.sampler(IncompatibleValueContract) { sampler }
        assertFailsWith<IllegalStateException> {
            samplerFirst.writer(CollisionContract.value) { }
        }
        val samplerBackend = samplerFirst.build()
        assertSame(sampler, samplerBackend.sampler(IncompatibleValueContract))
        assertNull(samplerBackend.writer(CollisionContract.value))
        assertSame(IncompatibleValueContract, samplerBackend.propertySpec(CollisionContract.value.name))
    }

    @Test
    fun metaAndObservedBatchReadersAreAlternativesAndKeepTheFirstProvider() = runTest {
        val descriptor = CollisionContract.value.descriptor
        var metaCalls = 0
        val metaFirst = DeviceBackendBuilder()
        metaFirst.batchMetaReader {
            metaCalls++
            mapOf(descriptor.name to OperationOutcome.Ok(metaOf(1.0)))
        }
        assertFailsWith<IllegalStateException> {
            metaFirst.batchObservedReader { emptyMap() }
        }
        val metaResult = metaFirst.build().bindCollisionStub().readBatchObserved(listOf(descriptor))
        assertEquals(1, metaCalls)
        assertEquals(1.0, metaResult.getValue(descriptor.name).getOrThrow().value?.doubleValue)

        var observedCalls = 0
        val observedFirst = DeviceBackendBuilder()
        observedFirst.batchObservedReader {
            observedCalls++
            mapOf(descriptor.name to OperationOutcome.Ok(ObservedValue(metaOf(2.0), Instant.DISTANT_PAST, DataQuality.GOOD)))
        }
        assertFailsWith<IllegalStateException> {
            observedFirst.batchMetaReader { emptyMap() }
        }
        val observedResult = observedFirst.build().bindCollisionStub().readBatchObserved(listOf(descriptor))
        assertEquals(1, observedCalls)
        assertEquals(2.0, observedResult.getValue(descriptor.name).getOrThrow().value?.doubleValue)
    }

    @Test
    fun compatibleCapabilitiesAndSameNameActionRemainComposable() = runTest {
        var value = 1.0
        val sampler = doubleSampler(capacity = 2)
        val quality = DataQuality.GOOD
        val time = Instant.fromEpochMilliseconds(10)
        val backend = deviceBackend {
            observedReader(CollisionContract.value) { ObservedValue(value, time, quality) }
            writer(CollisionContract.value) { value = it }
            sampler(CollisionContract.value) { sampler }
            action(SameNameActionContract) { "ack:$it" }
            batchObservedReader { descriptors ->
                descriptors.associate { descriptor ->
                    descriptor.name to OperationOutcome.Ok(ObservedValue(metaOf(value), time, quality))
                }
            }
            batchBinaryReader { emptyMap() }
            batchWriter { emptyMap() }
        }

        assertSame(sampler, backend.sampler(CollisionContract.value))
        assertEquals("ack:go", backend.action(SameNameActionContract)?.execute("go"))
        assertSame(CollisionContract.value, backend.propertySpec(CollisionContract.value.name))
        assertSame(SameNameActionContract, backend.actionSpec(CollisionContract.value.name))
        backend.writer(CollisionContract.value)?.write(7.0)
        assertEquals(7.0, backend.reader(CollisionContract.value)?.read())
        assertEquals(
            7.0,
            backend.bindCollisionStub()
                .readBatchObserved(listOf(CollisionContract.value.descriptor))
                .getValue(CollisionContract.value.name)
                .getOrThrow()
                .value
                ?.doubleValue,
        )

        val reverseOrder = deviceBackend {
            sampler(CollisionContract.value) { sampler }
            writer(CollisionContract.value) { value = it }
            reader(CollisionContract.value) { value }
        }
        assertSame(sampler, reverseOrder.sampler(CollisionContract.value))
        reverseOrder.writer(CollisionContract.value)?.write(8.0)
        assertEquals(8.0, reverseOrder.reader(CollisionContract.value)?.read())

        val binaryReverseOrder = deviceBackend {
            sampler(CollisionContract.value) { sampler }
            writer(CollisionContract.value) { value = it }
            bytesReader(CollisionContract.value) { byteArrayOf(1) }
        }
        assertSame(sampler, binaryReverseOrder.sampler(CollisionContract.value))
        binaryReverseOrder.writer(CollisionContract.value)?.write(9.0)
        assertEquals(9.0, value)
        assertNotNull(binaryReverseOrder.bindCollisionStub().readBinary(CollisionContract.value.descriptor))
    }
}
