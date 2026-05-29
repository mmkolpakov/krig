package space.kscience.krig.assembly

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.io.IOException
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.data.toDataQuality
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.Device
import kotlin.time.Clock

/** Runtime view of a validated [DataPlatformConfiguration]. */
public class DataPlatformRuntime(
    public val config: DataPlatformConfiguration,
    public val devices: Map<Name, Device>,
    public val clock: Clock = Clock.System,
    public val qualityPolicy: QualityPolicy = DefaultQualityPolicy,
) {
    public fun propertiesForTimer(timerId: Name): List<PropertySpec> = config.propertiesForTimer(timerId)

    public fun propertiesForTimer(timerId: String): List<PropertySpec> = propertiesForTimer(timerId.asName())
}

/** One sampled platform property. */
public data class PlatformObservation(
    public val property: PropertySpec,
    public val observed: ObservedValue<Meta?>,
    public val fault: OperationFault? = null,
) {
    public val isOk: Boolean get() = fault == null
}

public fun DataPlatformConfiguration.runtime(
    devices: Map<Name, Device>,
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): DataPlatformRuntime = DataPlatformRuntime(this, devices, clock, qualityPolicy)

public fun InstalledDataPlatform.runtime(
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): DataPlatformRuntime = config.runtime(devices, clock, qualityPolicy)

public fun DataPlatformConfiguration.propertiesForTimer(timerId: Name): List<PropertySpec> {
    val timer = timers.singleOrNull { it.id == timerId }
        ?: error("Data platform timer '$timerId' is not declared.")
    val propertiesById = properties.associateBy { it.id }
    return timer.properties.map { propertyId ->
        propertiesById[propertyId] ?: error("Data platform timer '$timerId' references unknown property '$propertyId'.")
    }
}

public fun DataPlatformConfiguration.propertiesForTimer(timerId: String): List<PropertySpec> =
    propertiesForTimer(timerId.asName())

public fun DataPlatformRuntime.pollTimer(
    timerId: Name,
    ticks: Flow<Unit>,
): Flow<PlatformObservation> {
    val timerProperties = propertiesForTimer(timerId)
    return ticks.transform {
        val observationsById = LinkedHashMap<Name, PlatformObservation>()
        for ((sourceId, properties) in timerProperties.groupBy { it.sourceId }) {
            observeSource(sourceId, properties).forEach { observation ->
                observationsById[observation.property.id] = observation
            }
        }
        for (property in timerProperties) {
            emit(observationsById.getValue(property.id))
        }
    }
}

public fun DataPlatformRuntime.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
): Flow<PlatformObservation> = pollTimer(timerId.asName(), ticks)

private suspend fun DataPlatformRuntime.observeSource(
    sourceId: Name,
    properties: List<PropertySpec>,
): List<PlatformObservation> = try {
    val device = devices[sourceId]
    if (device == null) {
        properties.map { property ->
            property.failed(
                clock,
                GenericOperationFault(message = "Unknown data platform source '$sourceId'."),
                qualityPolicy,
            )
        }
    } else {
        val outcomes = device.readBatchOutcome(properties.map { it.property })
        properties.map { property ->
            when (val outcome = outcomes[property.property]) {
                is OperationOutcome.Ok -> PlatformObservation(property = property, observed = outcome.value)
                is OperationOutcome.Fail -> property.failed(clock, outcome.fault, qualityPolicy)
                null -> property.failed(
                    clock,
                    GenericOperationFault(
                        message = "Device '$sourceId' did not return data platform property '${property.property}'.",
                    ),
                    qualityPolicy,
                )
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    properties.map { property -> property.failed(clock, e.fault, qualityPolicy) }
} catch (e: IOException) {
    val fault = TransportFault(
        causeType = e::class.simpleName ?: "IOException",
        message = e.message ?: "I/O failure while sampling data platform source '$sourceId'.",
    )
    properties.map { property -> property.failed(clock, fault, qualityPolicy) }
} catch (e: RuntimeException) {
    val fault = GenericOperationFault(
        message = e.message ?: "Runtime failure while sampling data platform source '$sourceId'.",
    )
    properties.map { property -> property.failed(clock, fault, qualityPolicy) }
}

private fun PropertySpec.failed(
    clock: Clock,
    fault: OperationFault,
    qualityPolicy: QualityPolicy,
): PlatformObservation =
    PlatformObservation(
        property = this,
        observed = ObservedValue(
            value = null,
            time = clock.now(),
            quality = fault.toDataQuality(QualityNamespaces.DataPlatform, qualityPolicy),
        ),
        fault = fault,
    )
