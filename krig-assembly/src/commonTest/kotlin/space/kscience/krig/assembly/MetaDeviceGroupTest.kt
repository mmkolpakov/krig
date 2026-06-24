@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
)

package space.kscience.krig.assembly

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class StubDevice(name: Name, context: Context) : AbstractDevice(name, DeviceRuntime(context)) {
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

private fun factoryContext(name: String): Context = Context(name) { plugin(DeviceFactoryPlugin) }

class MetaDeviceGroupTest {

    @Test
    fun buildsGroupFromMeta() {
        val context = factoryContext("meta-group")
        context.deviceFactories().register(
            DeviceFactory("stub") { childContext ->
                StubDevice(childContext.name, childContext)
            },
        )

        val group = context.metaDeviceGroup(
            "crate",
            Meta {
                "children" put {
                    "motor" put { "factory" put "stub" }
                    "sensor" put { "factory" put "stub" }
                }
            },
        )

        assertEquals(setOf("motor".asName(), "sensor".asName()), group.devices.keys)
        assertIs<StubDevice>(group.devices["motor".asName()])
    }

    @Test
    fun unknownFactoryFails() {
        val context = factoryContext("meta-group-missing")
        assertFailsWith<IllegalStateException> {
            context.metaDeviceGroup("crate", Meta { "children" put { "x" put { "factory" put "missing" } } })
        }
    }

    @Test
    fun missingFactoryKeyFails() {
        val context = factoryContext("meta-group-nokey")
        assertFailsWith<IllegalStateException> {
            context.metaDeviceGroup("crate", Meta { "children" put { "x" put { "note" put "no factory here" } } })
        }
    }
}
