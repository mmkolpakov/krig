@file:Suppress("unused")
@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.benchmarks.dataplane

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import java.util.Base64
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.readBinaryBlock

/** Meta ByteArray codec against raw binary bypass. */
@State(Scope.Benchmark)
open class RawBinaryBenchmark {
    private lateinit var property: PropertyDescriptor
    private lateinit var payload: ByteArray
    private lateinit var env: DeviceEnvironment
    private lateinit var metaBackend: DeviceBackend
    private lateinit var rawBackend: DeviceBackend

    @Setup
    open fun setup() {
        property = PropertyDescriptor(
            name = "waveform".asName(),
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.META,
        )
        payload = ByteArray(4096) { index -> (index % 256).toByte() }
        env = benchmarkEnvironment()
        metaBackend = MetaBinaryBackend(payload)
        rawBackend = RawBinaryBackendImpl(payload)
    }

    @Benchmark
    open fun metaByteArrayRead(blackhole: Blackhole): ByteArray = runBlocking {
        context(env) {
            val meta = metaBackend.read(property).getOrThrow()
            Base64.getDecoder().decode(MetaConverter.string.read(meta)).also(blackhole::consume)
        }
    }

    @Benchmark
    open fun rawBinaryRead(blackhole: Blackhole): ByteArray = runBlocking {
        context(env) {
            rawBackend.readBinaryBlock(property).toByteArray().also(blackhole::consume)
        }
    }
}

private class MetaBinaryBackend(private val payload: ByteArray) : UnsupportedBackend() {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
        OperationOutcome.Ok(MetaConverter.string.convert(Base64.getEncoder().encodeToString(payload)))
}

private class RawBinaryBackendImpl(private val payload: ByteArray) : UnsupportedBackend() {
    context(device: DeviceEnvironment)
    override suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> =
        OperationOutcome.Ok(payload.asBinary())
}

private open class UnsupportedBackend : DeviceBackend {
    context(device: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Benchmark backend does not expose Meta reads.",
            ),
        )

    context(device: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Benchmark backend does not expose writes.",
            ),
        )

    context(device: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Benchmark backend does not expose actions.",
            ),
        )

    override fun close() = Unit
}
