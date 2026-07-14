package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.meta.mutableDevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class StateModelBuilderTest {
    private data class State(var value: Double = 1.0)

    private val valueSpec: MutableDevicePropertyContract<Double> = mutableDevicePropertyContract(
        name = Name.of("value"),
        converter = MetaConverter.double,
        kind = PropertyKind.PHYSICAL,
        valueTypeId = TypeIds.DOUBLE,
    )

    private val stateConverter: MetaConverter<State> = object : MetaConverter<State> {
        override fun convert(obj: State) = MetaConverter.double.convert(obj.value)

        override fun readOrNull(source: space.kscience.dataforge.meta.Meta): State? =
            MetaConverter.double.readOrNull(source)?.let(::State)
    }

    @Test
    fun stateModelMapsStateToTypedReaderAndWriter() = runTest {
        val backend = stateModel(::State) {
            bind(
                valueSpec,
                read = { value },
                write = { value = it },
            )
        }

        val reader = assertNotNull(backend.reader(valueSpec))
        val writer = assertNotNull(backend.writer(valueSpec))

        assertEquals(1.0, reader.read())
        writer.write(7.0)
        assertEquals(7.0, reader.read())
    }

    @Test
    fun rejectedBindDoesNotLeaveAReaderAfterCaughtWriterConflict() = runTest {
        val state = State()
        val model = stateModel(stateConverter, { state }) {
            writer(valueSpec) { value = it }

            assertFailsWith<IllegalStateException> {
                bind(
                    valueSpec,
                    read = { value },
                    write = { value = it * 10.0 },
                )
            }
        }

        assertNull(model.backend.reader(valueSpec))
        val writer = assertNotNull(model.backend.writer(valueSpec))
        writer.write(7.0)
        assertEquals(7.0, state.value)

        model.reconstructible.applyEvent(
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(1),
                property = valueSpec.name,
                value = valueSpec.converter.convert(9.0),
                sourceDevice = Name.of("source"),
            ),
        )
        assertEquals(9.0, state.value)
    }

    @Test
    fun reconstructibleBindReplaysPropertyChanges() = runTest {
        val state = State()
        val model = stateModel(stateConverter, { state }) {
            bind(valueSpec, read = { value }, write = { value = it })
        }

        model.reconstructible.applyEvent(
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(1),
                property = valueSpec.name,
                value = valueSpec.converter.convert(11.0),
                sourceDevice = Name.of("source"),
            ),
        )

        assertEquals(11.0, state.value)
        assertEquals(11.0, assertNotNull(model.backend.reader(valueSpec)).read())
    }
}
