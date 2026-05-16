@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.operations

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.MutableCompositeDevice
import space.kscience.krig.core.runtime.reconcile
import space.kscience.krig.core.runtime.reconcileScoped
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalKrigApi::class, ExperimentalCoroutinesApi::class)
class DeviceReconcilerTest {

    private class TestDevice(name: Name) : AbstractDevice(name, DeviceRuntime(Context("device-$name"))) {
        override suspend fun readProperty(propertyName: Name) = error("Not used in test")
        override suspend fun writeProperty(propertyName: Name, value: space.kscience.dataforge.meta.Meta) = Unit
        override suspend fun execute(actionName: Name, argument: space.kscience.dataforge.meta.Meta?) = null
    }

    @Test
    fun reconcileAttachesAndDetachesToMatchDesiredSet() = runTest {
        val context = Context("hub-test")
        val hub = MutableCompositeDevice("hub".asName(), context)
        val desired = MutableStateFlow(setOf("motorA".asName()))

        val loop = with(context) {
            hub.reconcile(desired, produce = { name -> TestDevice(name) }, scope = this@runTest)
        }
        advanceUntilIdle()

        assertEquals(setOf("motorA".asName()), hub.children.keys)

        desired.value = setOf("motorB".asName())
        advanceUntilIdle()

        assertEquals(setOf("motorB".asName()), hub.children.keys)
        assertTrue("motorB".asName() in hub.children.keys)

        loop.job.cancel()
    }

    @Test
    fun reconcileScopedRollsBackProducerResourcesWhenProductionFails() = runTest {
        val context = Context("hub-scoped-rollback")
        val hub = MutableCompositeDevice("hub".asName(), context)
        val desired = MutableStateFlow(setOf("motorA".asName()))
        var rollbackCount = 0

        val loop = with(context) {
            hub.reconcileScoped(
                desired = desired,
                produce = {
                    onRollback { rollbackCount++ }
                    error("port failed")
                },
                scope = this@runTest,
            )
        }
        advanceUntilIdle()

        assertTrue(hub.children.isEmpty())
        assertEquals(1, rollbackCount)

        loop.job.cancel()
    }
}
