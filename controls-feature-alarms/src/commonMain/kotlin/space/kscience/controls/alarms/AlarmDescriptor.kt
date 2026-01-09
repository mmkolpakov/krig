package space.kscience.controls.alarms

import kotlinx.serialization.Serializable
import space.kscience.controls.api.structure.MemberDescriptor
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * A serializable, declarative specification for a single alarm condition within a device blueprint.
 *
 * @property predicateName The name of a boolean property (a `PREDICATE`) on the same device that triggers this alarm.
 * @property retainTime An optional duration for which the alarm should remain in an active state even after the
 *                      triggering predicate becomes false.
 * @property attributes TODO desc
 */
@Serializable
public data class AlarmDescriptor(
    override val name: Name,
    val description: String,
    val predicateName: Name,
    val severity: AlarmSeverity,
    val retainTime: Duration = Duration.ZERO,
    override val attributes: Meta = Meta.EMPTY
) : MemberDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}