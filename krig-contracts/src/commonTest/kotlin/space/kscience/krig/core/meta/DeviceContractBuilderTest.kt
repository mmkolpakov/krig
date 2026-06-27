package space.kscience.krig.core.meta

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.access
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceContractBuilderTest {
    @Serializable
    private data class Limits(val low: Double, val high: Double)

    private object Contract : DeviceContractBuilder() {
        val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        val load by property(MetaConverter.double, TypeIds.DOUBLE)
        val command by action(MetaConverter.string, MetaConverter.string)
    }

    @Test
    fun builderRecordsPurePropertyAndActionContracts() {
        assertEquals(listOf(Contract.value, Contract.load), Contract.propertyContracts)
        assertEquals(listOf(Contract.command), Contract.actionContracts)
        assertEquals(true, Contract.value.descriptor.access?.mutable)
        assertEquals(false, Contract.load.descriptor.access?.mutable)
    }

    @Test
    fun duplicatePropertyNameFailsEarly() {
        assertFailsWith<IllegalStateException> {
            object : DeviceContractBuilder() {
                init {
                    val contract = devicePropertyContract(
                        name = "value".asName(),
                        converter = MetaConverter.double,
                        kind = PropertyKind.PHYSICAL,
                        valueTypeId = TypeIds.DOUBLE,
                    )
                    assertEquals(contract, registerPropertyContract(contract))
                    assertEquals(contract, registerPropertyContract(contract))
                }
            }
        }
    }

    @Test
    fun descriptorsCanBeExportedToMaps() {
        val propertyMap = Contract.propertyContracts.descriptorMap()
        val actionMap = Contract.actionContracts.descriptorMap()

        assertEquals(Contract.value.descriptor, propertyMap.getValue(Contract.value.name))
        assertEquals(Contract.load.descriptor, propertyMap.getValue(Contract.load.name))
        assertEquals(Contract.command.descriptor, actionMap.getValue(Contract.command.name))
    }

    @Test
    fun serializablePropertyPopulatesMetaDescriptor() {
        val contract = object : DeviceContractBuilder() {
            val limits by serializableProperty<Limits>()
            val mutableLimits by serializableMutableProperty<Limits>()
        }

        assertEquals(setOf("low", "high"), contract.limits.descriptor.metaDescriptor.nodes.keys)
        assertEquals(setOf("low", "high"), contract.mutableLimits.descriptor.metaDescriptor.nodes.keys)
    }
}
