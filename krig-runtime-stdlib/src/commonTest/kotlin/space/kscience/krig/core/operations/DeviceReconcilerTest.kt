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
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.MutableDeviceHub
import space.kscience.krig.core.runtime.awaitChildren
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
        override suspend fun doReadPropertyOutcome(
            propertyName: Name,
        ): OperationOutcome<space.kscience.dataforge.meta.Meta> =
            runCatchingOperation { error("Not used in test") }

        override suspend fun doWritePropertyOutcome(
            propertyName: Name,
            value: space.kscience.dataforge.meta.Meta,
        ): OperationOutcome<Unit> = OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(
            actionName: Name,
            argument: space.kscience.dataforge.meta.Meta?,
        ): OperationOutcome<space.kscience.dataforge.meta.Meta?> = OperationOutcome.Ok(null)
    }

    @Test
    fun reconcileAttachesAndDetachesToMatchDesiredSet() = runTest {
        val context = Context("hub-test")
        val hub = MutableDeviceHub("hub".asName(), context)
        val desired = MutableStateFlow(setOf("motorA".asName()))

        val loop = context(context) {
            hub.reconcile(desired, produce = { name -> TestDevice(name) }, scope = this@runTest)
        }

        assertEquals(setOf("motorA".asName()), hub.awaitChildren(setOf("motorA".asName())).keys)

        desired.value = setOf("motorB".asName())

        assertEquals(setOf("motorB".asName()), hub.awaitChildren(setOf("motorB".asName())).keys)
        assertTrue("motorB".asName() in hub.devices.keys)

        loop.job.cancel()
    }

    @Test
    fun reconcileScopedRollsBackProducerResourcesWhenProductionFails() = runTest {
        val context = Context("hub-scoped-rollback")
        val hub = MutableDeviceHub("hub".asName(), context)
        val desired = MutableStateFlow(setOf("motorA".asName()))
        var rollbackCount = 0

        val loop = context(context) {
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

        assertTrue(hub.devices.isEmpty())
        assertEquals(1, rollbackCount)

        loop.job.cancel()
    }
}
