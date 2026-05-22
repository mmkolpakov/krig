package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StateModelBuilderTest {
    private data class State(var value: Double = 1.0)

    private object ValueSpec : MutableDevicePropertySpec<Device, Double> {
        override val name: Name = "value".asName()
        override val converter: MetaConverter<Double> = MetaConverter.double
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.PHYSICAL, valueTypeId = TypeIds.DOUBLE)

        override suspend fun read(device: Device): Double = error("not used")
        override suspend fun write(device: Device, value: Double): Unit = error("not used")
    }

    @Test
    fun stateModelMapsStateToTypedReaderAndWriter() = runTest {
        val backend = stateModel(::State) {
            bind(
                ValueSpec,
                read = { value },
                write = { value = it },
            )
        }

        val reader = assertNotNull(backend.reader(ValueSpec))
        val writer = assertNotNull(backend.writer(ValueSpec))

        assertEquals(1.0, reader.read())
        writer.write(7.0)
        assertEquals(7.0, reader.read())
    }
}
