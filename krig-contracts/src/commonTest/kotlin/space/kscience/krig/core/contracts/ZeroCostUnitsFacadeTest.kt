@file:OptIn(space.kscience.krig.core.KrigPerformancePitfall::class)

package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.core.contracts.sampling.RingDoubleSampler
import space.kscience.krig.core.meta.devicePropertyContract
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proof that domain (physical-unit) typing is expressible over the existing core without an extra
 * module and without hot-path allocation: a phantom `value class` over `Double` unboxes to a primitive,
 * so a typed facade over `RingDoubleSampler` and a typed `DevicePropertyContract` cost nothing extra.
 * The core stays domain-neutral while domain (physical-unit) typing layers on top without allocation.
 */
class ZeroCostUnitsFacadeTest {

    @JvmInline
    private value class Rpm(val value: Double)

    // Zero-cost facade over Kolpakov's unboxed ring: the inline call lowers to publishDouble(double).
    private fun RingDoubleSampler.publishRpm(rpm: Rpm) = publishDouble(rpm.value)
    private fun RingDoubleSampler.latestRpmOrNull(): Rpm? =
        latestDoubleOrNaN().let { if (it.isNaN()) null else Rpm(it) }

    private val rpmConverter = object : MetaConverter<Rpm> {
        override fun convert(obj: Rpm): Meta = MetaConverter.double.convert(obj.value)
        override fun readOrNull(source: Meta): Rpm? = MetaConverter.double.readOrNull(source)?.let(::Rpm)
    }

    @Test
    fun typedUnitFacadeOverRingSamplerRoundTrips() {
        val sampler = RingDoubleSampler(capacity = 8)
        assertNull(sampler.latestRpmOrNull())

        sampler.publishRpm(Rpm(1_500.0))
        assertEquals(Rpm(1_500.0), sampler.latestRpmOrNull())
    }

    @Test
    fun typedUnitContractRoundTripsThroughMeta() {
        val contract = devicePropertyContract(
            name = "rpm".asName(),
            converter = rpmConverter,
            kind = PropertyKind.MEASURED,
            valueTypeId = TypeId("unit.rpm"),
        )
        val encoded = contract.converter.convert(Rpm(3_000.0))
        assertEquals(Rpm(3_000.0), contract.converter.readOrNull(encoded))
        assertEquals(TypeId("unit.rpm"), contract.descriptor.valueTypeId)
    }
}
