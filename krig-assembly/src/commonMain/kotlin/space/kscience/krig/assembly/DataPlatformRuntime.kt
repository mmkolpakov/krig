package space.kscience.krig.assembly

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.io.IOException
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.contracts.Device
import kotlin.time.Clock

/** Runtime view of a validated [DataPlatformConfiguration]. */
public class DataPlatformRuntime(
    public val config: DataPlatformConfiguration,
    public val devices: Map<Name, Device>,
    public val clock: Clock = Clock.System,
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
): DataPlatformRuntime = DataPlatformRuntime(this, devices, clock)

public fun InstalledDataPlatform.runtime(clock: Clock = Clock.System): DataPlatformRuntime =
    config.runtime(devices, clock)

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
        for (property in timerProperties) {
            emit(observe(property))
        }
    }
}

public fun DataPlatformRuntime.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
): Flow<PlatformObservation> = pollTimer(timerId.asName(), ticks)

@OptIn(PerformancePitfall::class)
private suspend fun DataPlatformRuntime.observe(property: PropertySpec): PlatformObservation = try {
    val device = devices[property.sourceId]
        ?: return property.failed(clock, GenericOperationFault(message = "Unknown data platform source '${property.sourceId}'."))
    PlatformObservation(
        property = property,
        observed = ObservedValue(
            value = device.readProperty(property.property),
            time = clock.now(),
            quality = DataQuality.GOOD,
        ),
    )
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    property.failed(clock, e.fault)
} catch (e: IOException) {
    property.failed(
        clock,
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "I/O failure while sampling data platform property '${property.id}'.",
        ),
    )
} catch (e: RuntimeException) {
    property.failed(
        clock,
        GenericOperationFault(
            message = e.message ?: "Runtime failure while sampling data platform property '${property.id}'.",
        ),
    )
}

private fun PropertySpec.failed(clock: Clock, fault: OperationFault): PlatformObservation =
    PlatformObservation(
        property = this,
        observed = ObservedValue(value = null, time = clock.now(), quality = fault.toPlatformQuality()),
        fault = fault,
    )

private fun OperationFault.toPlatformQuality(): DataQuality {
    val qualityCode = faultType.toString().replace('.', '-')
    return DataQuality(
        severity = QualitySeverity.BAD,
        code = QualityCode("krig.data-platform.${qualityCode.lowercase()}"),
        detail = message,
    )
}
