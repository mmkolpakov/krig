@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.runtime

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.contracts.execute
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.hub.resolveDevice
import space.kscience.krig.api.hub.resolveNode
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.asNode
import space.kscience.krig.core.contracts.deviceTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

private val cdTestSeq: AtomicInt = AtomicInt(0)

class DeviceGroupTest {

    private class StubDevice(
        name: String,
        private val properties: Map<Name, Meta> = emptyMap(),
    ) : AbstractDevice(
        name.asName(),
        DeviceRuntime(Context("cd-${cdTestSeq.addAndFetch(1)}-$name")),
    ) {

        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(properties[propertyName] ?: Meta { "stub".asName() put name.toString() })

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(Meta { "action".asName() put actionName.toString() })
    }

    @Test
    fun readPropertyDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1", mapOf("temp".asName() to Meta { "value".asName() put 25 }))
        val child2 = StubDevice("child2", mapOf("pressure".asName() to Meta { "value".asName() put 1013 }))

        val group = DeviceGroup(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-test"),
            children = mapOf("child1".asName() to child1, "child2".asName() to child2),
        )

        val result = group.readProperty("child1.temp".parseAsName())
        assertEquals(25, result["value".asName()].int)

        val result2 = group.readProperty("child2.pressure".parseAsName())
        assertEquals(1013, result2["value".asName()].int)
    }

    @Test
    fun writePropertyDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1")
        val group = DeviceGroup(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-write"),
            children = mapOf("child1".asName() to child1),
        )
        group.writeProperty("child1.setpoint".parseAsName(), Meta { "value".asName() put 100 })
    }

    @Test
    fun executeDelegatesToCorrectChild() = runTest {
        val child1 = StubDevice("child1")
        val group = DeviceGroup(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-exec"),
            children = mapOf("child1".asName() to child1),
        )
        val result = group.execute("child1.reset".parseAsName(), null)
        assertEquals("reset", result?.get("action".asName())?.value?.toString())
    }

    @Test
    fun unknownChildThrowsError() = runTest {
        val group = DeviceGroup(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-fail"),
            children = mapOf("child1".asName() to StubDevice("child1")),
        )
        assertFailsWith<IllegalStateException> {
            group.readProperty("nonexistent.prop".parseAsName())
        }
    }

    @Test
    fun emptyPropertyNameThrowsError() = runTest {
        val group = DeviceGroup(
            name = "hub".asName(),
            context = Context("comp-${cdTestSeq.addAndFetch(1)}-empty"),
            children = mapOf("child1".asName() to StubDevice("child1")),
        )
        assertFailsWith<IllegalStateException> {
            group.readProperty(Name.EMPTY)
        }
    }

    @Test
    fun deviceTreeCanContainFolderNodesAndLeafDevices() {
        val pump = StubDevice("pump")
        val tree = deviceTree(
            children = mapOf(
                "line".asName() to deviceTree(
                    children = mapOf("pump".asName() to pump.asNode()),
                ),
            ),
        )

        assertSame(pump, tree.resolveDevice("line.pump".parseAsName()))
        assertNull(tree.resolveDevice("line".parseAsName()))
        assertEquals(setOf("pump".asName()), tree.resolveNode("line".parseAsName())?.children?.keys)
    }
}
