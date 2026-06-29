package space.kscience.krig.demo

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.KrigPerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.dsl.device

internal data class LabDiscoverySnapshot(
    val strictWriteAccepted: Boolean,
    val adHocPropertyVisible: Boolean,
    val discoveredGain: Double,
)

/** Schema-less Meta access for lab discovery before a stable manifest is available. */
@OptIn(KrigPerformancePitfall::class)
suspend fun labDiscoveryAdHocDemo() {
    val snapshot = labDiscoverySnapshot()

    println("=== Lab discovery ad-hoc Meta path ===")
    println("  strict write accepted: ${snapshot.strictWriteAccepted}")
    println("  synthetic property visible: ${snapshot.adHocPropertyVisible}")
    println("  discovered calibration gain: ${snapshot.discoveredGain}")
    println("\nDone - lab discovery ad-hoc demo complete.")
}

@OptIn(KrigPerformancePitfall::class)
internal suspend fun labDiscoverySnapshot(): LabDiscoverySnapshot {
    val ctx = demoContext("lab-discovery-demo")
    val backend = AdHocMetaBackend()
    val gainName = "calibration.gain".asName()

    val strict = device("strictProbe", backend, ctx) {
        allowAdHocProperties = false
    }
    val strictWrite = strict.writePropertyOutcome(gainName, metaOf(2.5))

    val discovery = device("discoveryProbe", backend, ctx) {
        allowAdHocProperties = true
    }
    discovery.writeProperty(gainName, metaOf(2.5))
    val discovered = discovery.readProperty(gainName)
    val visible = discovery.propertySpec(gainName) != null

    strict.close()
    discovery.close()
    ctx.close()

    return LabDiscoverySnapshot(
        strictWriteAccepted = strictWrite is OperationOutcome.Ok,
        adHocPropertyVisible = visible,
        discoveredGain = MetaConverter.double.read(discovered),
    )
}

@OptIn(UnstableKrigForSubclassing::class)
private class AdHocMetaBackend : DeviceBackend {
    private val values: MutableMap<Name, Meta> = linkedMapOf()

    context(env: DeviceEnvironment)
    override suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta> =
        values[property.name]?.let { OperationOutcome.Ok(it) } ?: OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownProperty,
                message = "No discovered value for property '${property.name}'.",
            ),
        )

    context(env: DeviceEnvironment)
    override suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit> {
        values[property.name] = value
        return OperationOutcome.OkUnit
    }

    context(env: DeviceEnvironment)
    override suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownAction,
                message = "Ad-hoc discovery backend has no action '${action.name}'.",
            ),
        )

    override fun close() = Unit
}
