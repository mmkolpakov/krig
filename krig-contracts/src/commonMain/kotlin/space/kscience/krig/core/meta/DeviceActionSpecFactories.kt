package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name

@PublishedApi
internal fun <D : Device, I, O> buildDeviceActionSpec(
    name: Name,
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    execute: suspend D.(I) -> O?,
): DeviceActionSpec<D, I, O> {
    val descriptor = ActionDescriptor(name = name)
    return object : DeviceActionSpec<D, I, O> {
        override val name: Name = name
        override val descriptor: ActionDescriptor = descriptor
        override val inputConverter: MetaConverter<I> = inputConverter
        override val outputConverter: MetaConverter<O> = outputConverter
        override suspend fun execute(device: D, input: I): O? = device.execute(input)
    }
}
