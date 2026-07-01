@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.demo

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.NoOpAuditService
import space.kscience.krig.assembly.DeviceCatalog
import space.kscience.krig.assembly.DeviceFactoryPlugin
import space.kscience.krig.assembly.deviceCatalog
import space.kscience.krig.assembly.deviceFactories
import space.kscience.krig.assembly.metaDeviceGroup
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.schemaHash

private class FactoryPumpDevice(name: Name, context: Context) : AbstractDevice(name, DeviceRuntime(context)) {
    private var rpm: Double = 900.0

    override val propertyDescriptors = PumpManifest.properties
    override val actionDescriptors = PumpManifest.actions

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        when (propertyName) {
            PumpSpec.rpm.name -> OperationOutcome.Ok(MetaConverter.double.convert(rpm))
            PumpSpec.load.name -> OperationOutcome.Ok(MetaConverter.double.convert(rpm / 3_000.0))
            else -> OperationOutcome.Ok(Meta.EMPTY)
        }

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> {
        if (propertyName == PumpSpec.rpm.name) rpm = MetaConverter.double.read(value)
        return OperationOutcome.OkUnit
    }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(MetaConverter.string.convert("ack:${argument?.let(MetaConverter.string::read).orEmpty()}"))
}

/** Data-driven factory topology: Meta input, typed topology spec internally, manifest guard in catalog. */
suspend fun factoryTopologyDemo() {
    val ctx = Context("factory-topology-demo") {
        plugin(AllowAllAuthorizationService)
        plugin(NoOpAuditService)
        plugin(DeviceFactoryPlugin)
        plugin(DeviceCatalog)
    }
    ctx.deviceCatalog().register(PumpManifest)
    ctx.deviceFactories().register(
        DeviceFactory("demo.pump") { childContext ->
            FactoryPumpDevice(childContext.name, childContext)
        },
    )

    val group = ctx.metaDeviceGroup(
        "lab",
        Meta {
            "children" put {
                "pumpA" put {
                    "factory" put "demo.pump"
                    "manifest" put {
                        "id" put PumpManifest.id.toString()
                        "version" put PumpManifest.version
                        "schemaHash" put PumpManifest.schemaHash()
                    }
                }
            }
        },
    )

    try {
        val pump = checkNotNull(group.devices["pumpA".asName()])
        println("=== Factory topology ===")
        println("  devices: ${group.devices.keys}")
        println("  pumpA rpm: ${pump.read(PumpSpec.rpm)}")
    } finally {
        group.close()
        ctx.close()
    }
    println("\nDone - factory topology demo complete.")
}
