@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
)

package space.kscience.krig.core.contracts

import kotlinx.atomicfu.atomic
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.contracts.typed.GenericTypedWriter
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind

/**
 * Minimal in-tree fixture: one atomic `Double` cell reachable through the typed reader /
 * writer surface (zero-allocation [GenericTypedReader]<Double> / [GenericTypedWriter]<Double>) and the legacy
 * Meta-boxed path. Test-only; lives in `commonTest` and is not published.
 */
class SimulatedDoubleSource(
    name: Name = "simulated-double-source".asName(),
    context: Context = Context(name.toString()),
) : AbstractDevice(name, DeviceRuntime(context)) {

    private val cell = atomic(0.0)

    /**
     * Primitive ring sampler — the driver-thread (here, every typed write) publishes the
     * latest value; consumers attach via `device.sampler(valueSpec)?.flow()` for streaming
     * or `latestDoubleOrNaN()` for non-suspending point reads.
     */
    private val valueSampler: RingDoubleSampler = doubleSampler(capacity = 256)

    /** The single [Double] property this fixture exposes. */
    val valueSpec: MutableDevicePropertySpec<SimulatedDoubleSource, Double> =
        object : MutableDevicePropertySpec<SimulatedDoubleSource, Double> {
            override val name: Name = "value".asName()
            override val descriptor: PropertyDescriptor = PropertyDescriptor(
                name = this.name,
                kind = PropertyKind.PHYSICAL,
                valueTypeId = TypeIds.DOUBLE,
            )
            override val converter: MetaConverter<Double> = MetaConverter.double
            override suspend fun read(device: SimulatedDoubleSource): Double = device.cell.value
            override suspend fun write(device: SimulatedDoubleSource, value: Double) {
                device.cell.value = value
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        if (spec === valueSpec) GenericTypedReader { cell.value } as TypedReader<T>
        else super.reader(spec)

    @Suppress("UNCHECKED_CAST")
    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T> =
        if (spec === valueSpec) GenericTypedWriter<Double> { v ->
            cell.value = v
            valueSampler.publishDouble(v)
        } as TypedWriter<T>
        else super.writer(spec)

    @Suppress("UNCHECKED_CAST")
    override fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? =
        if (spec === valueSpec) valueSampler as TypedSampler<T>
        else super.sampler(spec)

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
        if (propertyName == valueSpec.name) valueSpec else null

    override suspend fun readProperty(propertyName: Name): Meta =
        if (propertyName == valueSpec.name) MetaConverter.double.convert(cell.value)
        else error("Unknown property '$propertyName'")

    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        check(propertyName == valueSpec.name) { "Unknown property '$propertyName'" }
        val v = value.double ?: error("Property '$propertyName' expects a Double")
        cell.value = v
        valueSampler.publishDouble(v)
    }

    override suspend fun execute(actionName: Name, argument: Meta?): Meta =
        error("SimulatedDoubleSource has no actions")
}
