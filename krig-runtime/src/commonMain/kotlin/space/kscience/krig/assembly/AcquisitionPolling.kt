package space.kscience.krig.assembly

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Protocol-neutral reader used by external acquisition integrations. The SDK owns
 * scheduling and mapping; the integration owns connector state and address syntax.
 */
public fun interface AcquisitionTagReader {
    public suspend fun read(tag: AcquisitionTagSpec): OperationOutcome<Meta>
}

/** One polling result for a configured acquisition tag. */
public data class AcquisitionObservation(
    public val tag: AcquisitionTagSpec,
    public val observed: ObservedValue<Meta?>,
    public val fault: OperationFault? = null,
) {
    public val isOk: Boolean get() = fault == null
}

/** Resolves the configured tags sampled by [timerId], preserving timer order. */
public fun DataAcquisitionConfiguration.tagsForTimer(timerId: String): List<AcquisitionTagSpec> {
    val timer = timers.singleOrNull { it.id == timerId }
        ?: error("Acquisition timer '$timerId' is not declared.")
    val tagsById = tags.associateBy { it.id }
    return timer.tags.map { tagId ->
        tagsById[tagId] ?: error("Acquisition timer '$timerId' references unknown tag '$tagId'.")
    }
}

/**
 * Polls all tags attached to [timerId] whenever [ticks] emits. Timeouts declared
 * on [AcquisitionTagSpec.timeoutMs] become degraded observations instead of
 * cancelling the whole polling loop. Parent cancellation still propagates.
 */
public fun DataAcquisitionConfiguration.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
    clock: Clock = Clock.System,
    reader: AcquisitionTagReader,
): Flow<AcquisitionObservation> {
    val timerTags = tagsForTimer(timerId)
    return ticks.transform {
        for (tag in timerTags) {
            emit(reader.observe(tag, clock))
        }
    }
}

private suspend fun AcquisitionTagReader.observe(
    tag: AcquisitionTagSpec,
    clock: Clock,
): AcquisitionObservation = when (val outcome = readWithTimeout(tag)) {
    is OperationOutcome.Ok -> AcquisitionObservation(
        tag = tag,
        observed = ObservedValue(value = outcome.value, time = clock.now(), quality = DataQuality.GOOD),
    )
    is OperationOutcome.Fail -> AcquisitionObservation(
        tag = tag,
        observed = ObservedValue(value = null, time = clock.now(), quality = outcome.fault.toAcquisitionQuality()),
        fault = outcome.fault,
    )
}

private suspend fun AcquisitionTagReader.readWithTimeout(tag: AcquisitionTagSpec): OperationOutcome<Meta> = try {
    val timeoutMs = tag.timeoutMs
    if (timeoutMs == null) {
        read(tag)
    } else {
        withTimeout(timeoutMs.milliseconds) { read(tag) }
    }
} catch (_: TimeoutCancellationException) {
    fail(TimeoutFault())
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    fail(e.fault)
} catch (e: IOException) {
    fail(
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "I/O failure while reading acquisition tag '${tag.id}'.",
        )
    )
}

private fun OperationFault.toAcquisitionQuality(): DataQuality {
    val qualityCode = faultType.toString().replace('.', '-')
    return DataQuality(
        severity = QualitySeverity.BAD,
        code = QualityCode("krig.acquisition.${qualityCode.lowercase()}"),
        detail = message,
    )
}
