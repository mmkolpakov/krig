@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.api.factory

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.required
import space.kscience.dataforge.meta.descriptors.value
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertFalse

class DeviceFactoryTest {

    @Test
    fun factoryDescriptorIsConstructionConfigDescriptor() {
        val descriptor = MetaDescriptor {}
        val factory: DeviceFactory<Device, Unit> = DeviceFactory(
            id = "space.kscience.krig.test.factory",
            configDescriptor = descriptor,
        ) {
            error("Factory construction is not part of this descriptor test")
        }

        assertSame(descriptor, factory.descriptor)
        assertSame(descriptor, factory.configDescriptor)
    }

    @Test
    fun factoryBuildValidatesRawConfigBeforeCreation() {
        val descriptor = MetaDescriptor {
            value("port", ValueType.NUMBER) { required() }
        }
        var created = false
        val factory: DeviceFactory<Device, Unit> = DeviceFactory(
            id = "space.kscience.krig.test.validated-factory",
            configDescriptor = descriptor,
        ) {
            created = true
            StubDevice(it)
        }

        assertFailsWith<DeviceFactoryConfigValidationException> {
            factory.build(Context("invalid-factory-config"), Meta { "port" put "not-a-number" })
        }
        assertFalse(created, "Factory create() must not run after config validation failure")
    }

    private class StubDevice(context: Context) : AbstractDevice(
        name = "factory-test-stub".parseAsName(),
        runtime = DeviceRuntime(context),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(Meta.EMPTY)

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
    }
}
