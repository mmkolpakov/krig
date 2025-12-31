package space.kscience.controls.composite.dsl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.context.ExecutionContext
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.composite.old.asDataTree
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.messages.PropertyChangedMessage
import space.kscience.controls.core.descriptors.PropertyDescriptor
import space.kscience.controls.core.descriptors.PropertyKind
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.await
import space.kscience.dataforge.data.branch
import space.kscience.dataforge.data.get
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ObservableMutableMeta
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.*
import kotlin.time.Clock

/**
 * A mock [Device] implementation for testing the `asDataTree` adapter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class MockDataSourceDevice(scope: CoroutineScope) : Device by
TestDeviceImpl(Global, "dataSourceDevice".asName(), ObservableMutableMeta(), scope.coroutineContext) {

    var readCount = 0
        private set

    private val properties = mutableMapOf(
        "a".parseAsName() to "valueA",
        "b.c".parseAsName() to "valueBC",
        "b.d".parseAsName() to "valueBD"
    )

    override val propertyDescriptors = properties.keys.map {
        PropertyDescriptor(it, PropertyKind.PHYSICAL, "kotlin.String")
    }

    override val messageFlow = MutableSharedFlow<PropertyChangedMessage>()

    @InternalControlsApi
    override suspend fun readProperty(propertyName: Name, context: ExecutionContext): Meta {
        readCount++
        return Meta(properties[propertyName] ?: error("Property not found"))
    }
}


/**
 * Tests for the [space.kscience.controls.data.asDataTree] adapter.
 */
//all passed
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDataSourceTest {

    /**
     * Verifies that the structure of the generated [DataTree] correctly mirrors the hierarchical names of properties.
     */
    @Test
    fun testAsDataTreeStructure() = runTest {
        val device = MockDataSourceDevice(this)
        val dataTree = device.asDataTree()

        println(dataTree.items)
        println(dataTree.data)

        assertNotNull(dataTree.branch("a"), "Branch 'a' should exist")
        assertNotNull(dataTree["a".parseAsName()], "Node 'a' should be directly accessible")

        val branchB = dataTree.branch("b")
        assertNotNull(branchB, "Branch 'b' should exist")
        assertNull(branchB.data, "Branch node 'b' should not contain data itself.")
        assertTrue(branchB.items.isNotEmpty(), "Branch node 'b' should have children.")

        assertNotNull(branchB.branch("c"), "Branch 'b.c' should exist")
        assertNotNull(branchB["c".parseAsName()], "Node 'c' should be accessible from branch 'b'")

        assertNotNull(dataTree["b.c".parseAsName()], "Node 'b.c' should be accessible from the root")
        assertNotNull(dataTree["b.d".parseAsName()], "Node 'b.d' should be accessible from the root")
    }

    /**
     * Verifies that awaiting data from the tree triggers a `readProperty` call on the device.
     */
    @Test
    fun testAsDataTreeRead() = runTest {
        val device = MockDataSourceDevice(this)
        val dataTree = device.asDataTree()
        assertEquals(0, device.readCount)

        println(dataTree.items)
        println(dataTree.data)
        val data: Data<Meta>? = dataTree["b.c"]

        assertNotNull(data, "data for 'b.c' should not be null")
        val value = data.await()

        assertEquals("valueBC", value.string)
        assertEquals(1, device.readCount)
    }

    /**
     * Verifies that a [PropertyChangedMessage] from the device's flow is correctly propagated
     * as an update in the [DataTree].
     */
    @Test
    fun testAsDataTreeUpdates() = runTest {
        val device = MockDataSourceDevice(this)
        val dataTree = device.asDataTree()

        val job = launch {
            val updatedName = withTimeout(1000) { dataTree.updates.first() }
            assertEquals("b.c".asName(), updatedName)
        }

        device.messageFlow.emit(
            PropertyChangedMessage(
                property = "b.c",
                value = Meta("newValue"),
                sourceDevice = Address("hub", "dataSourceDevice"),
                time = Clock.System.now()
            )
        )

        job.join()
    }
}