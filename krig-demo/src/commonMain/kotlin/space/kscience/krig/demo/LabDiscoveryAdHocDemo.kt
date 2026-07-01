package space.kscience.krig.demo

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.KrigPerformancePitfall
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DynamicDiscoveryPolicy
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

    val strict = device("strictProbe", backend, ctx)
    val strictWrite = strict.writePropertyOutcome(gainName, metaOf(2.5))

    val discovery = device("discoveryProbe", backend, ctx) {
        dynamicDiscoveryPolicy = DynamicDiscoveryPolicy.AdHoc
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

    override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
        val boundEnvironment = environment
        return object : BoundDeviceBackend {
            override val environment: BackendEnvironment = boundEnvironment

            override suspend fun read(property: PropertyDescriptor): Meta =
                values[property.name] ?: fault(
                    OperationFaultTypes.UnknownProperty,
                    "No discovered value for property '${property.name}'.",
                )

            override suspend fun write(property: PropertyDescriptor, value: Meta) {
                values[property.name] = value
            }

            override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta? =
                fault(OperationFaultTypes.UnknownAction, "Ad-hoc discovery backend has no action '${action.name}'.")

            override fun close() = Unit
        }
    }

    private fun fault(type: Name, message: String): Nothing =
        throw OperationFaultException(GenericOperationFault(faultType = type, message = message))
}
