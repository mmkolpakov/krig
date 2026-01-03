package space.kscience.controls.fsm

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.controls.common.meta.SchemeAsMetaSerializer
import space.kscience.controls.api.spec.RestartStrategy
import space.kscience.dataforge.meta.*
import kotlin.time.Duration.Companion.seconds

/**
 * Defines the policy for restarting a failed device.
 *
 * @property maxAttempts The maximum number of restart attempts. `0` means no retries. A value less than 0 means infinite retries.
 * @property strategy The [RestartStrategy] to use for calculating delays between retries.
 * @property resetOnSuccess If true, the attempt counter is reset after a successful start.
 */
@Serializable(with = RestartPolicy.Serializer::class)
public class RestartPolicy : Scheme() {
    public var maxAttempts: Int by int(5)
    public var strategy: RestartStrategy by convertable(
        MetaConverter.serializable(TODO()),
        default = RestartStrategy.Linear(2.seconds)
    )
    public var resetOnSuccess: Boolean by boolean(true)


    /**
     * The effective number of attempts to be used by a runtime.
     * Treats non-positive `maxAttempts` as [Int.MAX_VALUE] to represent infinity in a loop-safe manner.
     */
    @Transient
    public val effectiveMaxAttempts: Int
        get() = if (maxAttempts <= 0) Int.MAX_VALUE else maxAttempts

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherMeta = (other as? MetaRepr)?.toMeta() ?: return false
        return Meta.equals(this.toMeta(), otherMeta)
    }

    override fun hashCode(): Int {
        return Meta.hashCode(this.toMeta())
    }

    public companion object : SchemeSpec<RestartPolicy>(::RestartPolicy) {
        public val DEFAULT: RestartPolicy = RestartPolicy()
    }

    /**
     * Custom serializer for RestartPolicy that delegates to Meta serialization.
     */
    public object Serializer : SchemeAsMetaSerializer<RestartPolicy>(RestartPolicy)
}