@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.contracts

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val cdTestSeq: AtomicInt = AtomicInt(0)

class CompositeDeviceTest {

    private class StubDevice(
        name: String,
        private val properties: Map<Name, Meta> = emptyMap(),
    ) : AbstractDevice(
        name.asName(),
        DeviceRuntime(Context("cd-${cdTestSeq.addAndFetch(1)}-$name")),
    ) {

        override suspend fun readProperty(propertyName: Name): Meta =
            properties[propertyName] ?: Meta { "stub".asName() put name.toString() }

        override suspend fun writeProperty(propertyName: Name, value: Meta) {}

        override suspend fun execute(actionName: Name, argument: Meta?): Meta =
            Meta { "action".asName() put actionName.toString() }
    }

    @Test
    fun readPropertyDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1", mapOf("temp".asName() to Meta { "value".asName() put 25 }))
        val child2 = StubDevice("child2", mapOf("pressure".asName() to Meta { "value".asName() put 1013 }))

        val composite = CompositeDevice(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-test"),
            children = mapOf("child1".asName() to child1, "child2".asName() to child2),
        )

        val result = composite.readProperty("child1.temp".parseAsName())
        assertEquals(25, result["value".asName()].int)

        val result2 = composite.readProperty("child2.pressure".parseAsName())
        assertEquals(1013, result2["value".asName()].int)
    }

    @Test
    fun writePropertyDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1")
        val composite = CompositeDevice(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-write"),
            children = mapOf("child1".asName() to child1),
        )
        composite.writeProperty("child1.setpoint".parseAsName(), Meta { "value".asName() put 100 })
    }

    @Test
    fun executeDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1")
        val composite = CompositeDevice(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-exec"),
            children = mapOf("child1".asName() to child1),
        )
        val result = composite.execute("child1.reset".parseAsName(), null)
        assertEquals("reset", result?.get("action".asName())?.value?.toString())
    }

    @Test
    fun unknownChildThrowsError() = runTest {
        val composite = CompositeDevice(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-fail"),
            children = mapOf("child1".asName() to StubDevice("child1")),
        )
        assertFailsWith<IllegalStateException> {
            composite.readProperty("nonexistent.prop".parseAsName())
        }
    }

    @Test
    fun emptyPropertyNameThrowsError() = runTest {
        val composite = CompositeDevice(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-empty"),
            children = mapOf("child1".asName() to StubDevice("child1")),
        )
        assertFailsWith<IllegalStateException> {
            composite.readProperty(Name.EMPTY)
        }
    }
}
