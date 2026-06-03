package space.kscience.krig.assembly

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Reduction applied when a sink collapses several samples into one grid bin. Identified by [id]
 * so producers and consumers agree on the algebra: built-ins are resolved by the `sealed` set,
 * [Named] is resolved against the caller's `Map<Name, Reducer>`.
 */
@Serializable
public sealed interface ReductionSpec {
    public val id: Name

    @Serializable
    @SerialName("last")
    public data object Last : ReductionSpec {
        override val id: Name = "last".asName()
    }

    @Serializable
    @SerialName("mean")
    public data object Mean : ReductionSpec {
        override val id: Name = "mean".asName()
    }

    @Serializable
    @SerialName("minMaxMean")
    public data object MinMaxMean : ReductionSpec {
        override val id: Name = "minMaxMean".asName()
    }

    @Serializable
    @SerialName("named")
    public data class Named(
        val name: Name,
        val config: Meta = Meta.EMPTY,
    ) : ReductionSpec {
        override val id: Name get() = name
    }
}
