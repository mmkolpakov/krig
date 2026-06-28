@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.api.factory

import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.krig.core.contracts.Device
import kotlin.test.Test
import kotlin.test.assertSame

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
}
