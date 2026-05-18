package space.kscience.krig.core.meta

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceContractBuilderTest {
    private object PumpContract : DeviceContractBuilder() {
        val rpm by mutableDoubleProperty()
        val load by doubleProperty()
        val command by action(MetaConverter.string, MetaConverter.string)
    }

    @Test
    fun pureContractsDoNotRequireExecutableDeviceLogic() {
        assertEquals(2, PumpContract.propertyContracts.size)
        assertEquals(1, PumpContract.actionContracts.size)
        assertEquals("rpm", PumpContract.rpm.name.toString())
        assertEquals("load", PumpContract.load.name.toString())
        assertEquals("command", PumpContract.command.name.toString())
    }

    @Test
    fun duplicateContractsFailFast() {
        val builder = object : DeviceContractBuilder() {}
        val contract = devicePropertyContract(
            name = "duplicate".parseAsName(),
            converter = MetaConverter.meta,
            kind = space.kscience.krig.api.descriptors.PropertyKind.LOGICAL,
            valueTypeId = space.kscience.krig.api.descriptors.TypeIds.META,
        )
        builder.registerPropertyContract(contract).let { }

        assertFailsWith<IllegalStateException> {
            builder.registerPropertyContract(contract)
        }
    }
}
