package space.kscience.controls.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.MemberAttribute
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * Attributes describing the performance requirements and capabilities of a device member (Property or Action).
 *
 * These attributes allow the system architect to strictly define which parts of the system
 * must operate in a real-time, zero-allocation manner (Fast Path).
 *
 * @property critical If `true`, the runtime must throw an exception during initialization if the
 *                    underlying driver does not provide a direct, optimized accessor for this member.
 *                    Use this for control loops where garbage collection overhead or boxing is unacceptable.
 * @property pollingIntervalMs A hint for the runtime or client indicating the expected frequency
 *                             of polling for this property in milliseconds.
 */
@Serializable
@SerialName("attr.performance")
public data class PerformanceAttribute(
    val critical: Boolean = false,
    val pollingIntervalMs: Long? = null
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}