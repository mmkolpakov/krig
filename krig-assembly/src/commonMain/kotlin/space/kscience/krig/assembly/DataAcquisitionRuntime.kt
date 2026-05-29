@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.assembly

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.data.toDataQuality
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.hub.DeviceHub
import space.kscience.krig.dsl.ClockState
import space.kscience.krig.dsl.systemClockState
import kotlin.time.Duration.Companion.milliseconds

/** A single sampled property value produced by [DataAcquisitionRuntime]. */
public data class AcquiredSample(
    public val propertyId: Name,
    public val sourceId: Name,
    public val property: Name,
    public val value: ObservedValue<Meta?>,
    public val fault: OperationFault? = null,
) {
    public val isOk: Boolean get() = fault == null
}

/**
 * Thin polling facade over [DeviceHub] and [DataPlatformConfiguration].
 *
 * It intentionally owns no storage, connector, or scheduler architecture: timers come from
 * [ClockState], devices come from the existing hub, and callers decide where samples go.
 */
public class DataAcquisitionRuntime(
    public val hub: DeviceHub,
    public val config: DataPlatformConfiguration,
    public val clockState: ClockState = systemClockState(),
    private val scope: CoroutineScope = hub.deviceScope,
    public val qualityPolicy: QualityPolicy = DefaultQualityPolicy,
) {
    private val propertySpecs: Map<Name, PropertySpec> = config.properties.associateBy { it.id }

    init {
        val errors = config.validate()
        require(errors.isEmpty()) {
            "DataPlatformConfiguration is invalid:\n  - ${errors.joinToString("\n  - ")}"
        }
    }

    /** Cold stream for one configured timer. */
    public fun samples(timer: TimerSpec): Flow<AcquiredSample> = flow {
        val properties = timer.properties.map { propertyId ->
            propertySpecs[propertyId] ?: error("Timer '${timer.id}' references unknown property '$propertyId'")
        }
        clockState.ticks(timer.intervalMs.milliseconds).collect {
            val samplesById = LinkedHashMap<Name, AcquiredSample>()
            for ((sourceId, group) in properties.groupBy { it.sourceId }) {
                readSamples(sourceId, group).forEach { sample ->
                    samplesById[sample.propertyId] = sample
                }
            }
            for (property in properties) {
                emit(samplesById.getValue(property.id))
            }
        }
    }

    /** Cold merged stream for all timers in [config]. */
    public fun samples(): Flow<AcquiredSample> =
        if (config.timers.isEmpty()) emptyFlow() else config.timers.map(::samples).merge()

    /** Launches the merged sampling stream in the runtime scope. */
    public fun launch(collector: suspend (AcquiredSample) -> Unit): Job =
        scope.launch { samples().collect(collector) }

    private suspend fun readSamples(sourceId: Name, properties: List<PropertySpec>): List<AcquiredSample> {
        val now = clockState.clock.now()
        val device = hub.devices[sourceId]
        if (device == null) {
            val missingDeviceFault = GenericOperationFault(message = "Unknown acquisition source '$sourceId'.")
            return properties.map { property -> property.failed(missingDeviceFault, now) }
        }
        return try {
            val outcomes = device.readBatchOutcome(properties.map { it.property })
            properties.map { property ->
                when (val outcome = outcomes[property.property]) {
                    is OperationOutcome.Ok -> AcquiredSample(
                        propertyId = property.id,
                        sourceId = property.sourceId,
                        property = property.property,
                        value = outcome.value,
                    )
                    is OperationOutcome.Fail -> property.failed(outcome.fault)
                    null -> property.failed(
                        GenericOperationFault(
                            message = "Device '${property.sourceId}' did not return acquisition property '${property.property}'.",
                        ),
                    )
                }
            }
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            val fault = GenericOperationFault(
                message = cause.message ?: "Runtime failure while sampling acquisition source '$sourceId'.",
            )
            properties.map { property -> property.failed(fault) }
        }
    }

    private fun PropertySpec.failed(
        fault: OperationFault,
        time: kotlin.time.Instant = clockState.clock.now(),
    ): AcquiredSample =
        AcquiredSample(
            propertyId = id,
            sourceId = sourceId,
            property = property,
            value = ObservedValue(null, time, fault.toDataQuality(QualityNamespaces.Acquisition, qualityPolicy)),
            fault = fault,
        )
}
