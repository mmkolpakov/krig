package space.kscience.krig.assembly

import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.faults.OperationFault

/**
 * One sampled item produced by a polling graph — unifies the former `PlatformObservation` and
 * `AcquisitionObservation`. [spec] is the typed source binding ([PropertySpec] for the KRig-native
 * data platform, [AcquisitionTagSpec] for protocol-neutral acquisition); the polling domain stays in
 * the spec type, so the two graphs share one observation carrier without erasing their semantics.
 */
public data class SamplingObservation<out S>(
    public val spec: S,
    public val observed: ObservedValue<Meta?>,
    public val fault: OperationFault? = null,
) {
    public val isOk: Boolean get() = fault == null
}
